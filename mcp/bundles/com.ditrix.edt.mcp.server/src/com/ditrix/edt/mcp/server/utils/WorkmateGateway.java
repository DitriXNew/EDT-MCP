/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Version-localized reflective adapter for 1C:Workmate 1.0.5.
 * <p>
 * Workmate is deliberately absent from EDT-MCP's target platform. Every class,
 * constructor, field and method belonging to Workmate is therefore named and
 * accessed only in this class through OSGi/reflection. In particular, Workmate's
 * public {@code BaseActivator.injectMembers(Object)} cannot provide a
 * {@code ConversationFacade} without declaring a compile-time typed injection
 * point. Since there is no public injector/facade getter, this adapter reads the
 * single private {@code injectorRef} field and asks the live Guice injector for
 * the facade instance.
 */
public class WorkmateGateway
{
    private static final String AI_BUNDLE = "com.e1c.edt.ai"; //$NON-NLS-1$
    private static final String UI_COMMON_BUNDLE = "com.e1c.edt.ai.ui.common"; //$NON-NLS-1$
    private static final String UI_BUNDLE = "com.e1c.edt.ai.ui"; //$NON-NLS-1$

    private static final String BASE_ACTIVATOR = "com.e1c.edt.ai.ui.BaseActivator"; //$NON-NLS-1$
    private static final String CONVERSATION_FACADE = "com.e1c.edt.ai.ConversationFacade"; //$NON-NLS-1$
    private static final String PROJECT_ID = "com.e1c.edt.ai.assistent.model.ProjectId"; //$NON-NLS-1$
    private static final String CONVERSATION_SESSION =
        "com.e1c.edt.ai.assistent.ConversationSession"; //$NON-NLS-1$
    private static final String SEND_REQUEST =
        "com.e1c.edt.ai.assistent.SendUserMessageRequest"; //$NON-NLS-1$
    private static final String SEND_RESULT =
        "com.e1c.edt.ai.assistent.SendMessageResult"; //$NON-NLS-1$
    private static final String CANCELLATION_TOKEN = "com.e1c.edt.ai.ICancellationToken"; //$NON-NLS-1$
    private static final String GUICE_INJECTOR = "com.google.inject.Injector"; //$NON-NLS-1$

    /**
     * Skill that makes the conversation facade run Workmate's tool loop instead of
     * answering from the model alone. It is the skill Workmate's own autopilot uses.
     */
    public static final String DEFAULT_SKILL = "custom"; //$NON-NLS-1$

    /**
     * Stable id under which {@link #ensureChatSession()} registers a JShell session.
     * <p>
     * Workmate's agentic chat has the {@code JShell} tool but NOT {@code JShellSession},
     * so it can execute code yet cannot obtain the session id that execution requires -
     * a deadlock only an outside party can break. Registering one session under a
     * constant id lets the project rules name it literally, with no file to read and no
     * value to pass around.
     */
    public static final String CHAT_SESSION_ID = "edt-mcp"; //$NON-NLS-1$

    /** Manual id the rules pair with {@link #CHAT_SESSION_ID}; stable across restarts. */
    public static final String CHAT_MANUAL_ID = "jshell_edt_canonical_imports"; //$NON-NLS-1$

    private static final String SESSION_MANAGER =
        "com.e1c.edt.ai.tools.IJShellSessionManager"; //$NON-NLS-1$

    private static final String GUAVA_CACHE = "com.google.common.cache.Cache"; //$NON-NLS-1$
    private static final String SETTINGS = "com.e1c.edt.ai.ISettings"; //$NON-NLS-1$
    private static final String CHAT = "com.e1c.edt.ai.ui.IChat"; //$NON-NLS-1$
    private static final String AI_CONTEXT = "com.e1c.edt.ai.AIContext"; //$NON-NLS-1$
    private static final String MCP_TOOLS = "com.e1c.edt.ai.IMcpTools"; //$NON-NLS-1$
    private static final String MCP_TOOL_CALLS =
        "com.e1c.edt.ai.assistent.model.McpToolCalls"; //$NON-NLS-1$
    private static final String MCP_TOOL_CALL =
        "com.e1c.edt.ai.assistent.model.McpToolCall"; //$NON-NLS-1$
    private static final String MCP_TOOL_FUNCTION_CALL =
        "com.e1c.edt.ai.assistent.model.McpToolCallFunctionCall"; //$NON-NLS-1$

    /**
     * How long the hand-off to the SWT thread may take. This bounds only the hand-off - opening
     * the view and posting the question - never Workmate's own work, which continues in its chat.
     */
    private static final int CHAT_HANDOFF_TIMEOUT_SECONDS = 30;

    /** Receives real milestones reached by the reflective Workmate adapter. */
    @FunctionalInterface
    public interface ProgressListener
    {
        /** @param message completed adapter milestone */
        void onProgress(String message);
    }

    /** Kinds of runtime failure that the MCP tool turns into actionable errors. */
    public enum FailureKind
    {
        NOT_INSTALLED,
        DISABLED,
        NO_CLIENT_TOKEN,
        INCOMPATIBLE,
        NOT_READY,
        TIMED_OUT,
        CALL_FAILED
    }

    /** Checked adapter failure carrying a stable category and a diagnostic detail. */
    public static class GatewayException extends Exception
    {
        private static final long serialVersionUID = 1L;

        private final FailureKind kind;
        private final String detail;

        private GatewayException(FailureKind kind, String detail)
        {
            super(detail);
            this.kind = kind;
            this.detail = detail;
        }

        public FailureKind getKind()
        {
            return kind;
        }

        public String getDetail()
        {
            return detail;
        }

        public static GatewayException notInstalled(String detail)
        {
            return new GatewayException(FailureKind.NOT_INSTALLED, detail);
        }

        public static GatewayException disabled(String detail)
        {
            return new GatewayException(FailureKind.DISABLED, detail);
        }

        public static GatewayException noClientToken(String detail)
        {
            return new GatewayException(FailureKind.NO_CLIENT_TOKEN, detail);
        }

        public static GatewayException incompatible(String detail)
        {
            return new GatewayException(FailureKind.INCOMPATIBLE, detail);
        }

        public static GatewayException notReady(String detail)
        {
            return new GatewayException(FailureKind.NOT_READY, detail);
        }

        public static GatewayException timedOut()
        {
            return new GatewayException(FailureKind.TIMED_OUT, "conversation future timed out"); //$NON-NLS-1$
        }

        public static GatewayException callFailed(String detail)
        {
            return new GatewayException(FailureKind.CALL_FAILED, detail);
        }
    }

    /** Immutable response returned to the tool after the reflective call succeeds. */
    public static class WorkmateResponse
    {
        private final String text;
        private final String reasoning;
        private final Integer assistantMessageCount;

        public WorkmateResponse(String text, String reasoning)
        {
            this(text, reasoning, null);
        }

        public WorkmateResponse(String text, String reasoning, Integer assistantMessageCount)
        {
            this.text = text;
            this.reasoning = reasoning;
            this.assistantMessageCount = assistantMessageCount;
        }

        public String getText()
        {
            return text;
        }

        public String getReasoning()
        {
            return reasoning;
        }

        /**
         * Returns Workmate's own assistant-message count. It is not relabelled as
         * a tool-round count because the reflective result does not prove those
         * concepts are identical.
         *
         * @return assistant-message count, or {@code null} when supplied by a test/older caller
         */
        public Integer getAssistantMessageCount()
        {
            return assistantMessageCount;
        }
    }

    /**
     * Sends one new conversation request through Workmate's own full tool loop.
     *
     * @param project optional EDT project; {@code null} selects ProjectId.Default
     * @param question user message
     * @param maxToolRounds optional Workmate tool-round limit
     * @param skillName optional Workmate skill name
     * @param timeoutSeconds wall-clock wait bound
     * @return Workmate text and optional reasoning
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
        String skillName, int timeoutSeconds) throws GatewayException
    {
        return ask(project, question, maxToolRounds, skillName, timeoutSeconds, message -> {
            // The compatibility overload has no progress consumer.
        });
    }

    /**
     * Sends one new conversation request and reports only milestones actually
     * completed by the reflective adapter.
     *
     * @param project optional EDT project; {@code null} selects ProjectId.Default
     * @param question user message
     * @param maxToolRounds optional Workmate tool-round limit
     * @param skillName optional Workmate skill name
     * @param timeoutSeconds total remaining job budget used to await Workmate
     * @param progress milestone listener
     * @return Workmate text, optional reasoning and assistant-message count
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
        String skillName, int timeoutSeconds, ProgressListener progress) throws GatewayException
    {
        try
        {
            Bundle aiBundle = requireBundle(AI_BUNDLE);
            Bundle uiCommonBundle = requireBundle(UI_COMMON_BUNDLE);
            requireBundle(UI_BUNDLE);
            progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$

            Object injector = resolveInjector(uiCommonBundle);

            Class<?> injectorClass = requireClass(uiCommonBundle, GUICE_INJECTOR);
            Method getInstance = requireMethod(injectorClass, "getInstance", Class.class); //$NON-NLS-1$

            // Refuse BEFORE building a conversation: an off switch or a missing key is a
            // user-fixable setup problem, and Workmate's own cloud call would otherwise fail
            // deep inside the future with a message that does not name the fix.
            requireEnabledAndAuthorized(aiBundle, injector, getInstance);
            progress.onProgress("Verified that Workmate is enabled and holds an access key."); //$NON-NLS-1$

            Class<?> facadeClass = requireClass(aiBundle, CONVERSATION_FACADE);
            Object facade = invoke(getInstance, injector, facadeClass);
            if (facade == null)
            {
                throw GatewayException.notReady("Guice returned no " + CONVERSATION_FACADE); //$NON-NLS-1$
            }
            progress.onProgress("Obtained the Workmate conversation facade."); //$NON-NLS-1$

            Class<?> projectIdClass = requireClass(aiBundle, PROJECT_ID);
            Object projectId = project == null
                ? readField(requirePublicField(projectIdClass, "Default"), null) //$NON-NLS-1$
                : create(requireConstructor(projectIdClass, PROJECT_ID + "(IProject)", //$NON-NLS-1$
                    IProject.class), project);

            Class<?> sessionClass = requireClass(aiBundle, CONVERSATION_SESSION);
            Class<?> requestClass = requireClass(aiBundle, SEND_REQUEST);
            Constructor<?> requestConstructor = requireConstructor(requestClass,
                SEND_REQUEST + "(ProjectId,String,ConversationSession,boolean,String,Boolean,Integer)", //$NON-NLS-1$
                projectIdClass, String.class, sessionClass, boolean.class, String.class,
                Boolean.class, Integer.class);
            Object request = create(requestConstructor, projectId, question, null, true,
                // chat = FALSE matches Workmate's OWN default (ConversationFacade maps a null
                // getChat() to false), and it is NOT what decides whether Workmate works the task
                // with its tools: with TRUE and with FALSE alike, a "raw" request came back in
                // ~1.2 s with assistantMessages = 1 and no tool round at all.
                //
                // The SKILL is what decides it, measured live against Workmate 1.0.5. Under
                // ConversationFacade's own default "raw" the cloud answers from the model alone.
                // Under DEFAULT_SKILL the same facade runs Workmate's full tool loop: the model
                // called JShellManual, JShellSession and JShell, reached this plugin through
                // IEdtMcpBridge and answered from real EDT-MCP output (7 assistant messages).
                // Not every name is accepted - "chat"/"agent"/"git-review" are refused by the
                // cloud with "Failed to create conversation" in ~35 ms - so do not treat this as
                // a free-form field.
                skillName == null || skillName.isEmpty() ? DEFAULT_SKILL : skillName,
                Boolean.FALSE, maxToolRounds);

            Class<?> cancellationTokenClass = requireClass(aiBundle, CANCELLATION_TOKEN);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Object cancellationToken = createCancellationToken(cancellationTokenClass, cancelled);
            Method sendAsync = requireMethod(facadeClass, "sendAsync", requestClass, //$NON-NLS-1$
                cancellationTokenClass);
            Object futureValue = invoke(sendAsync, facade, request, cancellationToken);
            if (!(futureValue instanceof CompletableFuture<?>))
            {
                throw GatewayException.incompatible("method '" + CONVERSATION_FACADE //$NON-NLS-1$
                    + ".sendAsync' returned " + typeName(futureValue) //$NON-NLS-1$
                    + " instead of CompletableFuture"); //$NON-NLS-1$
            }
            progress.onProgress("Sent the request to Workmate."); //$NON-NLS-1$

            Object sendResult;
            CompletableFuture<?> future = (CompletableFuture<?>) futureValue;
            try
            {
                sendResult = future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                cancelled.set(true);
                future.cancel(true);
                throw GatewayException.timedOut();
            }
            catch (InterruptedException e)
            {
                cancelled.set(true);
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw GatewayException.callFailed("the waiting thread was interrupted"); //$NON-NLS-1$
            }
            catch (ExecutionException e)
            {
                throw GatewayException.callFailed(rootCauseMessage(e));
            }

            if (sendResult == null)
            {
                throw GatewayException.callFailed("sendAsync completed without a result"); //$NON-NLS-1$
            }
            Class<?> resultClass = requireClass(aiBundle, SEND_RESULT);
            String text = stringValue(invoke(requireMethod(resultClass, "getText"), sendResult)); //$NON-NLS-1$
            String reasoning = stringValue(
                invoke(requireMethod(resultClass, "getReasoning"), sendResult)); //$NON-NLS-1$
            Integer assistantMessageCount = integerValue(invoke(
                requireMethod(resultClass, "getAssistantMessageCount"), sendResult)); //$NON-NLS-1$
            progress.onProgress("Received the Workmate response."); //$NON-NLS-1$
            return new WorkmateResponse(text, reasoning, assistantMessageCount);
        }
        catch (GatewayException e)
        {
            throw e;
        }
        catch (RuntimeException | LinkageError e)
        {
            throw GatewayException.callFailed(rootCauseMessage(e));
        }
    }

    /**
     * Invokes one of WORKMATE'S OWN tools directly, with no language model in the loop.
     * <p>
     * Workmate's cloud model decides for itself whether to use a tool, and live runs showed it
     * declining or inventing output instead. This path removes that decision: it goes straight to
     * Workmate's {@code IMcpToolInvoker}, the same component its skills use, so the tool either
     * runs or reports its own error. That also makes it the only way to obtain values the model
     * cannot get here - notably a {@code repl_session_id} from {@code JShellSession}, which
     * {@code JShell} requires and refuses to run without.
     *
     * @param toolName exact Workmate tool name, e.g. {@code JShellSession} or {@code JShell}
     * @param argsJson JSON OBJECT with that tool's arguments; blank means no arguments
     * @param timeoutSeconds how long to wait for the tool
     * @param progress milestone listener
     * @return the tool's own textual result
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public String callWorkmateTool(String toolName, String argsJson, int timeoutSeconds,
        ProgressListener progress) throws GatewayException
    {
        try
        {
            Bundle aiBundle = requireBundle(AI_BUNDLE);
            Bundle uiCommonBundle = requireBundle(UI_COMMON_BUNDLE);
            requireBundle(UI_BUNDLE);
            progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$

            Object injector = resolveInjector(uiCommonBundle);
            Class<?> injectorClass = requireClass(uiCommonBundle, GUICE_INJECTOR);
            Method getInstance = requireMethod(injectorClass, "getInstance", Class.class); //$NON-NLS-1$

            requireEnabledAndAuthorized(aiBundle, injector, getInstance);
            progress.onProgress("Verified that Workmate is enabled and holds an access key."); //$NON-NLS-1$

            // Call IMcpTools rather than Workmate's IMcpToolInvoker: the invoker collapses the
            // answer to details.responseMarkdown when present, which for JShellSession is the
            // human sentence "Code session created" and DROPS the repl_session_id that JShell
            // then demands. Going one layer lower keeps the raw content.
            String arguments = argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson; //$NON-NLS-1$
            Class<?> callsClass = requireClass(aiBundle, MCP_TOOL_CALLS);
            Class<?> callClass = requireClass(aiBundle, MCP_TOOL_CALL);
            Class<?> functionClass = requireClass(aiBundle, MCP_TOOL_FUNCTION_CALL);

            Object function = create(requireConstructor(functionClass, MCP_TOOL_FUNCTION_CALL
                + "()")); //$NON-NLS-1$
            setField(functionClass, function, "name", toolName); //$NON-NLS-1$
            setField(functionClass, function, "arguments", arguments); //$NON-NLS-1$

            Object call = create(requireConstructor(callClass, MCP_TOOL_CALL + "()")); //$NON-NLS-1$
            setField(callClass, call, "type", "function"); //$NON-NLS-1$ //$NON-NLS-2$
            setField(callClass, call, "id", "edt_mcp_" + toolName); //$NON-NLS-1$ //$NON-NLS-2$
            setField(callClass, call, "function", function); //$NON-NLS-1$

            Object calls = create(requireConstructor(callsClass, MCP_TOOL_CALLS + "()")); //$NON-NLS-1$
            if (!(calls instanceof Collection))
            {
                throw GatewayException.incompatible(MCP_TOOL_CALLS + " is not a Collection but " //$NON-NLS-1$
                    + typeName(calls));
            }
            @SuppressWarnings("unchecked")
            Collection<Object> callList = (Collection<Object>)calls;
            callList.add(call);

            Class<?> toolsClass = requireClass(aiBundle, MCP_TOOLS);
            Object tools = invoke(getInstance, injector, toolsClass);
            if (tools == null)
            {
                throw GatewayException.notReady("Guice returned no " + MCP_TOOLS); //$NON-NLS-1$
            }
            Class<?> cancellationTokenClass = requireClass(aiBundle, CANCELLATION_TOKEN);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Object token = createCancellationToken(cancellationTokenClass, cancelled);
            Method callTools = requireMethod(toolsClass, "callTools", callsClass, //$NON-NLS-1$
                cancellationTokenClass);
            progress.onProgress("Invoking Workmate tool '" + toolName + "' directly."); //$NON-NLS-1$ //$NON-NLS-2$

            Object futureValue = invoke(callTools, tools, calls, token);
            if (!(futureValue instanceof CompletableFuture<?>))
            {
                throw GatewayException.incompatible("method '" + MCP_TOOLS //$NON-NLS-1$
                    + ".callTools' returned " + typeName(futureValue) //$NON-NLS-1$
                    + " instead of CompletableFuture"); //$NON-NLS-1$
            }
            CompletableFuture<?> future = (CompletableFuture<?>)futureValue;
            Object result;
            try
            {
                result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                cancelled.set(true);
                future.cancel(true);
                throw GatewayException.timedOut();
            }
            catch (InterruptedException e)
            {
                cancelled.set(true);
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw GatewayException.callFailed("the waiting thread was interrupted"); //$NON-NLS-1$
            }
            catch (ExecutionException e)
            {
                throw GatewayException.callFailed(rootCauseMessage(e.getCause() == null
                    ? e : e.getCause()));
            }
            progress.onProgress("Workmate tool '" + toolName + "' returned."); //$NON-NLS-1$ //$NON-NLS-2$
            return extractToolText(result, toolName);
        }
        catch (RuntimeException | LinkageError e)
        {
            throw GatewayException.callFailed(rootCauseMessage(e));
        }
    }

    /**
     * Builds the minimal non-null {@code AIContext} the chat needs when the question comes from
     * outside an editor: a project, no document, and EMPTY (not null) text fields, because
     * Workmate reads members such as {@code getPrefix()} without a null check.
     *
     * @param aiBundle the {@code com.e1c.edt.ai} bundle
     * @param contextClass the resolved {@code AIContext} class
     * @param project optional project; {@code null} selects {@code ProjectId.Default}
     * @return a usable empty context
     * @throws GatewayException when the expected constructor is missing
     */
    private static Object createEmptyContext(Bundle aiBundle, Class<?> contextClass,
        IProject project) throws GatewayException
    {
        Class<?> projectIdClass = requireClass(aiBundle, PROJECT_ID);
        Object projectId = project == null
            ? readField(requirePublicField(projectIdClass, "Default"), null) //$NON-NLS-1$
            : create(requireConstructor(projectIdClass, PROJECT_ID + "(IProject)", //$NON-NLS-1$
                IProject.class), project);
        Constructor<?> constructor = requireConstructor(contextClass,
            AI_CONTEXT + "(ProjectId,int,String,int,String,String,int,String,String,int,int," //$NON-NLS-1$
                + "IDocument,Supplier)", //$NON-NLS-1$
            projectIdClass, int.class, String.class, int.class, String.class, String.class,
            int.class, String.class, String.class, int.class, int.class, IDocument.class,
            Supplier.class);
        Supplier<Boolean> notDisposed = () -> Boolean.FALSE;
        return create(constructor, projectId, Integer.valueOf(0), "", Integer.valueOf(0), //$NON-NLS-1$
            "", "", Integer.valueOf(0), "", "", Integer.valueOf(0), Integer.valueOf(0), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            null, notDisposed);
    }

    /**
     * Reads Workmate's live Guice injector out of its bundle activator.
     * <p>
     * This is the one place that touches a private member. {@code BaseActivator} exposes
     * {@code injectMembers(Object)} but no injector getter, and using {@code injectMembers} would
     * require compiling against Workmate's types - which this project deliberately does not do, so
     * that the Workmate bundles stay out of the target platform and CI never depends on 1C's
     * server. Keeping the reflection here means a Workmate refactoring breaks exactly one method,
     * with an INCOMPATIBLE refusal that names the member it could not find.
     *
     * @param uiCommonBundle the {@code com.e1c.edt.ai.ui.common} bundle
     * @return Workmate's live injector, never {@code null}
     * @throws GatewayException when the activator or the field is missing, or no injector exists yet
     */
    /**
     * Makes sure a JShell session is reachable under {@link #CHAT_SESSION_ID}, so
     * Workmate's chat can run code against this plugin's bridge without ever obtaining
     * a session id of its own.
     * <p>
     * The session itself is created through the public
     * {@code IJShellSessionManager.getOrCreateSession(null)}. Only the second step -
     * re-keying it - reaches into the manager's private {@code cache} field, because
     * the public API generates a random UUID and offers no way to choose one. The
     * value is put through Guava's PUBLIC {@code Cache} interface, and
     * {@code getSession} resolves ids as cache keys, so the constant id then behaves
     * like any other. Calling this repeatedly is cheap and idempotent: the session is
     * only rebuilt after Workmate evicts it (12 h idle, or 16 newer sessions).
     *
     * @return the constant session id, once it is live
     * @throws GatewayException when Workmate is missing, not ready, or its session
     *             manager no longer matches this adapter
     */
    public String ensureChatSession() throws GatewayException
    {
        try
        {
            Bundle uiCommonBundle = requireBundle(UI_COMMON_BUNDLE);
            Object injector = resolveInjector(uiCommonBundle);
            Class<?> injectorClass = requireClass(uiCommonBundle, GUICE_INJECTOR);
            Method getInstance = requireMethod(injectorClass, "getInstance", Class.class); //$NON-NLS-1$

            Class<?> managerClass = requireClass(uiCommonBundle, SESSION_MANAGER);
            Object manager = invoke(getInstance, injector, managerClass);
            if (manager == null)
            {
                throw GatewayException.notReady("Workmate's JShell session manager is not " //$NON-NLS-1$
                    + "available yet"); //$NON-NLS-1$
            }

            Method getSession = requireMethod(managerClass, "getSession", String.class); //$NON-NLS-1$
            if (invoke(getSession, manager, CHAT_SESSION_ID) != null)
            {
                return CHAT_SESSION_ID;
            }

            // Resolve everything the re-keying needs BEFORE creating a session. Creating one
            // is a side effect that cannot be undone - invalidating a session key makes
            // Workmate's removal listener CLOSE it - so a structure mismatch discovered
            // afterwards would leave an orphan session behind on every retry.
            Field cacheField = requirePrivateField(manager.getClass(), "cache"); //$NON-NLS-1$
            Object cache = readField(cacheField, manager);
            if (cache == null)
            {
                throw GatewayException.incompatible("field '" //$NON-NLS-1$
                    + manager.getClass().getName() + ".cache' is empty"); //$NON-NLS-1$
            }
            Class<?> cacheClass = requireClass(uiCommonBundle, GUAVA_CACHE);
            Method put = requireMethod(cacheClass, "put", Object.class, Object.class); //$NON-NLS-1$

            Method getOrCreate =
                requireMethod(managerClass, "getOrCreateSession", String.class); //$NON-NLS-1$
            Object session = invoke(getOrCreate, manager, (String)null);
            if (session == null)
            {
                throw GatewayException.notReady("Workmate returned no JShell session"); //$NON-NLS-1$
            }

            // The session now answers to TWO keys: the UUID Workmate generated for it, and
            // ours. The generated one is deliberately left in place - dropping it would run
            // the removal listener and close the session - so this costs one extra entry of
            // Workmate's 16, and only while no constant session exists yet.
            invoke(put, cache, CHAT_SESSION_ID, session);

            if (invoke(getSession, manager, CHAT_SESSION_ID) == null)
            {
                throw GatewayException.incompatible("the session did not become reachable " //$NON-NLS-1$
                    + "under id '" + CHAT_SESSION_ID + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return CHAT_SESSION_ID;
        }
        catch (GatewayException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            throw GatewayException.callFailed("could not register the chat JShell session: " //$NON-NLS-1$
                + e);
        }
    }

    private static Object resolveInjector(Bundle uiCommonBundle) throws GatewayException
    {
        Class<?> baseActivatorClass = requireClass(uiCommonBundle, BASE_ACTIVATOR);
        Object activator = invoke(requireMethod(baseActivatorClass, "getDefault"), null); //$NON-NLS-1$
        if (activator == null)
        {
            throw GatewayException.notReady("BaseActivator.getDefault() returned null"); //$NON-NLS-1$
        }
        Field injectorRefField = requirePrivateField(baseActivatorClass, "injectorRef"); //$NON-NLS-1$
        Object injectorReference = readField(injectorRefField, activator);
        if (!(injectorReference instanceof AtomicReference<?>))
        {
            throw GatewayException.incompatible("field '" + BASE_ACTIVATOR //$NON-NLS-1$
                + ".injectorRef' has unexpected type " + typeName(injectorReference)); //$NON-NLS-1$
        }
        Object injector = ((AtomicReference<?>)injectorReference).get();
        if (injector == null)
        {
            throw GatewayException.notReady("field '" + BASE_ACTIVATOR //$NON-NLS-1$
                + ".injectorRef' is empty"); //$NON-NLS-1$
        }
        return injector;
    }

    /**
     * Hands the question to Workmate's AGENTIC chat instead of the one-shot conversation facade.
     * <p>
     * {@code IChat.askQuestion} is what Workmate's own UI actions call: it opens the chat view and
     * drives the cloud-hosted chat app, which works the task with Workmate's tools and can search
     * and edit the configuration. The trade-off is that the method returns {@code void} - the
     * answer is rendered in the chat panel for a human and never comes back to Java - so this path
     * delivers a question, it does not produce an answer.
     *
     * @param project optional EDT project the chat should treat as context; {@code null} selects
     *            Workmate's default project
     * @param question the user question, already validated as non-blank
     * @param progress milestone listener
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public void pushToChat(IProject project, String question, ProgressListener progress)
        throws GatewayException
    {
        try
        {
            Bundle aiBundle = requireBundle(AI_BUNDLE);
            Bundle uiCommonBundle = requireBundle(UI_COMMON_BUNDLE);
            requireBundle(UI_BUNDLE);
            progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$

            Object injector = resolveInjector(uiCommonBundle);
            Class<?> injectorClass = requireClass(uiCommonBundle, GUICE_INJECTOR);
            Method getInstance = requireMethod(injectorClass, "getInstance", Class.class); //$NON-NLS-1$

            requireEnabledAndAuthorized(aiBundle, injector, getInstance);
            progress.onProgress("Verified that Workmate is enabled and holds an access key."); //$NON-NLS-1$

            Class<?> chatClass = requireClass(uiCommonBundle, CHAT);
            Object chat = invoke(getInstance, injector, chatClass);
            if (chat == null)
            {
                throw GatewayException.notReady("Guice returned no " + CHAT); //$NON-NLS-1$
            }
            Class<?> contextClass = requireClass(aiBundle, AI_CONTEXT);
            Method askQuestion = requireMethod(chatClass, "askQuestion", contextClass, //$NON-NLS-1$
                String.class);

            // A null AIContext is NOT safe, even though Chat.chat wraps it in
            // Optional.ofNullable: a live run with null logged
            // 'Cannot invoke "com.e1c.edt.ai.AIContext.getPrefix()"' inside Workmate and the
            // question never reached the chat. Build an EMPTY-but-real context instead - no
            // editor, no selection, empty text - which is what "asked from outside an editor"
            // actually means.
            Object aiContext = createEmptyContext(aiBundle, contextClass, project);
            progress.onProgress("Obtained the Workmate chat."); //$NON-NLS-1$

            // Chat.chat(...) calls IUI.showView(...) before dispatching, so it must start on the
            // SWT thread. A null AIContext is what Workmate itself tolerates - it wraps the value
            // in Optional.ofNullable - and it means "no editor selection", which is exactly our case.
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(1);
            Display.getDefault().asyncExec(() -> {
                try
                {
                    askQuestion.invoke(chat, aiContext, question);
                }
                catch (Exception | LinkageError e)
                {
                    failure.set(e);
                }
                finally
                {
                    delivered.countDown();
                }
            });
            if (!delivered.await(CHAT_HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                throw GatewayException.timedOut();
            }
            Throwable error = failure.get();
            if (error != null)
            {
                throw GatewayException.callFailed(rootCauseMessage(error));
            }
            progress.onProgress("Delivered the question to the Workmate chat view."); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw GatewayException.callFailed("the waiting thread was interrupted"); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError e)
        {
            throw GatewayException.callFailed(rootCauseMessage(e));
        }
    }

    /**
     * Refuses when Workmate is installed but cannot answer: switched off, or holding no access
     * key. Both are read through Workmate's own PUBLIC {@code ISettings} contract
     * ({@code isEnabled()} / {@code hasClientToken()}), not through a preference key of ours, so
     * the answer is whatever Workmate itself would act on.
     *
     * @param aiBundle the {@code com.e1c.edt.ai} bundle
     * @param injector Workmate's live Guice injector
     * @param getInstance the resolved {@code Injector.getInstance(Class)} method
     * @throws GatewayException when Workmate is disabled, unauthorized, or shaped unexpectedly
     */
    private static void requireEnabledAndAuthorized(Bundle aiBundle, Object injector,
        Method getInstance) throws GatewayException
    {
        Class<?> settingsClass = requireClass(aiBundle, SETTINGS);
        Object settings = invoke(getInstance, injector, settingsClass);
        if (settings == null)
        {
            throw GatewayException.notReady("Guice returned no " + SETTINGS); //$NON-NLS-1$
        }
        if (!readBoolean(settingsClass, settings, "isEnabled")) //$NON-NLS-1$
        {
            throw GatewayException.disabled(SETTINGS + ".isEnabled() is false"); //$NON-NLS-1$
        }
        if (!readBoolean(settingsClass, settings, "hasClientToken")) //$NON-NLS-1$
        {
            throw GatewayException.noClientToken(SETTINGS + ".hasClientToken() is false"); //$NON-NLS-1$
        }
    }

    /** Reads a no-argument boolean getter, refusing a non-boolean answer as a shape change. */
    private static boolean readBoolean(Class<?> type, Object target, String methodName)
        throws GatewayException
    {
        Object value = invoke(requireMethod(type, methodName), target);
        if (!(value instanceof Boolean))
        {
            throw GatewayException.incompatible("method '" + type.getName() + '.' + methodName //$NON-NLS-1$
                + "' returned " + typeName(value) + " instead of boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ((Boolean)value).booleanValue();
    }

    private static Bundle requireBundle(String symbolicName) throws GatewayException
    {
        Bundle owner = FrameworkUtil.getBundle(WorkmateGateway.class);
        BundleContext context = owner != null ? owner.getBundleContext() : null;
        if (context == null)
        {
            throw GatewayException.notInstalled(
                "EDT-MCP is not running in an active OSGi BundleContext"); //$NON-NLS-1$
        }
        for (Bundle bundle : context.getBundles())
        {
            if (symbolicName.equals(bundle.getSymbolicName()))
            {
                return bundle;
            }
        }
        throw GatewayException.notInstalled("required OSGi bundle '" + symbolicName //$NON-NLS-1$
            + "' was not found"); //$NON-NLS-1$
    }

    private static Class<?> requireClass(Bundle bundle, String className) throws GatewayException
    {
        try
        {
            return bundle.loadClass(className);
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            throw GatewayException.incompatible("class '" + className + "' was not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Method requireMethod(Class<?> type, String name, Class<?>... parameterTypes)
        throws GatewayException
    {
        try
        {
            return type.getMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException | SecurityException e)
        {
            throw GatewayException.incompatible("method '" + type.getName() + "." + name //$NON-NLS-1$ //$NON-NLS-2$
                + signature(parameterTypes) + "' was not found or is not public"); //$NON-NLS-1$
        }
    }

    private static Constructor<?> requireConstructor(Class<?> type, String displayName,
        Class<?>... parameterTypes) throws GatewayException
    {
        try
        {
            return type.getConstructor(parameterTypes);
        }
        catch (NoSuchMethodException | SecurityException e)
        {
            throw GatewayException.incompatible("constructor '" + displayName //$NON-NLS-1$
                + "' was not found or is not public"); //$NON-NLS-1$
        }
    }

    private static Field requirePrivateField(Class<?> type, String name) throws GatewayException
    {
        try
        {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException | RuntimeException e)
        {
            throw GatewayException.incompatible("field '" + type.getName() + "." + name //$NON-NLS-1$ //$NON-NLS-2$
                + "' was not found or could not be accessed"); //$NON-NLS-1$
        }
    }

    /** Assigns a public field by name, so a Workmate model object can be built reflectively. */
    private static void setField(Class<?> type, Object target, String name, Object value)
        throws GatewayException
    {
        Field field = requirePublicField(type, name);
        try
        {
            field.set(target, value);
        }
        catch (IllegalAccessException | IllegalArgumentException e)
        {
            throw GatewayException.incompatible("field '" + type.getName() + '.' + name //$NON-NLS-1$
                + "' could not be set: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Pulls the text out of Workmate's {@code McpCallToolsResult}, preferring the RAW content
     * over {@code details.responseMarkdown}: the markdown is the human sentence and drops
     * machine-readable values such as {@code repl_session_id}.
     *
     * @param result the value returned by {@code IMcpTools.callTools}
     * @param toolName the tool that produced it, for the error message
     * @return the tool's text, never {@code null}
     * @throws GatewayException when the result carries no message at all
     */
    private static String extractToolText(Object result, String toolName) throws GatewayException
    {
        if (result == null)
        {
            throw GatewayException.callFailed("Workmate tool '" + toolName //$NON-NLS-1$
                + "' returned no result"); //$NON-NLS-1$
        }
        Object messages = readField(requirePublicField(result.getClass(), "messages"), result); //$NON-NLS-1$
        if (!(messages instanceof Collection) || ((Collection<?>)messages).isEmpty())
        {
            throw GatewayException.callFailed("Workmate tool '" + toolName //$NON-NLS-1$
                + "' returned an empty message list"); //$NON-NLS-1$
        }
        Object message = ((Collection<?>)messages).iterator().next();
        Object content = readField(requirePublicField(message.getClass(), "content"), message); //$NON-NLS-1$
        if (content != null && !content.toString().isEmpty())
        {
            return content.toString();
        }
        Object details = readField(requirePublicField(message.getClass(), "details"), message); //$NON-NLS-1$
        if (details != null)
        {
            Object markdown = readField(
                requirePublicField(details.getClass(), "responseMarkdown"), details); //$NON-NLS-1$
            if (markdown != null)
            {
                return markdown.toString();
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static Field requirePublicField(Class<?> type, String name) throws GatewayException
    {
        try
        {
            return type.getField(name);
        }
        catch (NoSuchFieldException | SecurityException e)
        {
            throw GatewayException.incompatible("field '" + type.getName() + "." + name //$NON-NLS-1$ //$NON-NLS-2$
                + "' was not found or is not public"); //$NON-NLS-1$
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments)
        throws GatewayException
    {
        try
        {
            return method.invoke(target, arguments);
        }
        catch (IllegalAccessException e)
        {
            throw GatewayException.incompatible("method '" + method.getDeclaringClass().getName() //$NON-NLS-1$
                + "." + method.getName() + "' could not be accessed"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (InvocationTargetException e)
        {
            throw GatewayException.callFailed(rootCauseMessage(e));
        }
    }

    private static Object create(Constructor<?> constructor, Object... arguments)
        throws GatewayException
    {
        try
        {
            return constructor.newInstance(arguments);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw GatewayException.incompatible("constructor '" //$NON-NLS-1$
                + constructor.getDeclaringClass().getName() + "' could not be invoked"); //$NON-NLS-1$
        }
        catch (InvocationTargetException e)
        {
            throw GatewayException.callFailed(rootCauseMessage(e));
        }
    }

    private static Object readField(Field field, Object target) throws GatewayException
    {
        try
        {
            return field.get(target);
        }
        catch (IllegalAccessException e)
        {
            throw GatewayException.incompatible("field '" + field.getDeclaringClass().getName() //$NON-NLS-1$
                + "." + field.getName() + "' could not be accessed"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Object createCancellationToken(Class<?> tokenClass, AtomicBoolean cancelled)
    {
        return Proxy.newProxyInstance(tokenClass.getClassLoader(), new Class<?>[] {tokenClass},
            (proxy, method, args) -> {
                if ("isCanceled".equals(method.getName())) //$NON-NLS-1$
                {
                    return cancelled.get();
                }
                if ("toString".equals(method.getName())) //$NON-NLS-1$
                {
                    return "EDT-MCP Workmate cancellation token"; //$NON-NLS-1$
                }
                if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) //$NON-NLS-1$
                {
                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                }
                return null;
            });
    }

    private static String signature(Class<?>[] parameterTypes)
    {
        StringBuilder result = new StringBuilder("("); //$NON-NLS-1$
        for (int i = 0; i < parameterTypes.length; i++)
        {
            if (i > 0)
            {
                result.append(',');
            }
            result.append(parameterTypes[i].getSimpleName());
        }
        return result.append(')').toString();
    }

    private static String rootCauseMessage(Throwable throwable)
    {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause)
        {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
            ? cause.getClass().getSimpleName() : message;
    }

    private static String typeName(Object value)
    {
        return value == null ? "null" : value.getClass().getName(); //$NON-NLS-1$
    }

    private static String stringValue(Object value)
    {
        return value != null ? value.toString() : null;
    }

    private static Integer integerValue(Object value) throws GatewayException
    {
        if (value instanceof Number)
        {
            return ((Number)value).intValue();
        }
        throw GatewayException.incompatible("SendMessageResult.getAssistantMessageCount() " //$NON-NLS-1$
            + "returned " + typeName(value) + " instead of a number"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
