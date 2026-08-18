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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.ditrix.edt.mcp.server.bridge.BridgeActivity;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
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
    /**
     * The id Workmate generated for the session published under {@link #CHAT_SESSION_ID},
     * remembered so that key can be kept warm too. Volatile: written by whichever thread
     * publishes, read by the next pass.
     */
    private volatile String generatedSessionId;

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
     * How many times a plan-shaped answer is pushed to continue in the SAME conversation before
     * the last one is reported as the result. Five is Workmate's own number: its {@code
     * DevAutopilot} drives the very same facade with {@code while (autoContinue <= 5)}.
     */
    private static final int MAX_CONTINUATIONS = 5;

    /**
     * The nudge sent as the continuation message. It is Russian because it is addressed to
     * Workmate's model, whose conversation runs in the IDE language — it is data for that model,
     * not surface text. Same intent as {@code DevAutopilot}'s own continuation prompt (answer with
     * the result, not with a plan), with one deliberate difference: it does NOT order a tool call.
     * Measured live, "continue with tools" kept a model that wanted a documentation search — a
     * tool this toolset does not have — announcing that search five times over. Naming the escape
     * hatch instead ("if the tool you need is unavailable, answer from your own knowledge") is
     * what turns the last continuation into an answer.
     */
    private static final String CONTINUATION_PROMPT =
        "\u041E\u0442\u0432\u0435\u0442\u044C \u043D\u0430 \u0438\u0441\u0445\u043E\u0434\u043D\u044B\u0439 \u0432\u043E\u043F\u0440\u043E\u0441 \u0418\u0422\u041E\u0413\u041E\u0412\u042B\u041C \u0442\u0435\u043A\u0441\u0442\u043E\u043C " //$NON-NLS-1$
        + "\u043F\u0440\u044F\u043C\u043E \u0441\u0435\u0439\u0447\u0430\u0441. \u041D\u0435 \u043E\u043F\u0438\u0441\u044B\u0432\u0430\u0439 \u043D\u0430\u043C\u0435\u0440\u0435\u043D\u0438\u044F \u0438 \u043D\u0435 \u043F\u0438\u0448\u0438 " //$NON-NLS-1$
        + "\u043F\u043B\u0430\u043D. \u0415\u0441\u043B\u0438 \u043D\u0443\u0436\u043D\u044B\u0439 \u0438\u043D\u0441\u0442\u0440\u0443\u043C\u0435\u043D\u0442 \u043D\u0435\u0434\u043E\u0441\u0442\u0443\u043F\u0435\u043D, " //$NON-NLS-1$
        + "\u043E\u0442\u0432\u0435\u0442\u044C \u0438\u0437 \u0441\u043E\u0431\u0441\u0442\u0432\u0435\u043D\u043D\u044B\u0445 \u0437\u043D\u0430\u043D\u0438\u0439."; //$NON-NLS-1$

    /**
     * Above this length an answer is taken at face value. An announcement of intent is short by
     * nature ("I will look it up in the documentation"); a real answer that happens to contain
     * one of the markers below is not, and must never be thrown away by a continuation.
     */
    private static final int PLAN_TEXT_MAX_CHARS = 400;

    /**
     * First-person announcements of intent, lowercase. A SHORT answer containing one of these is
     * Workmate saying what it is about to do — the exact shape issue #427 reported ("For a full
     * reference ... I will use the 1C documentation search"), which the platform then never
     * followed up on its own.
     *
     * <p>First person is the discipline of this list, and the reason an inclusive imperative
     * ("let us use an index") is deliberately absent: a FINISHED short recommendation opens with
     * one as readily as a plan does, and a continuation is not free - it can run Workmate's tools
     * again, and its own answer then REPLACES the one already in hand.
     */
    /**
     * Announcements turned into their opposite. A short answer containing one of these is a
     * decision NOT to act - the one shape that looks exactly like an intent marker and means the
     * opposite of one, so it is checked first and wins.
     */
    /**
     * The sentinel Workmate is asked to put at the end of a FINAL answer.
     *
     * <p>This is the signal the platform does not give us: {@code SendMessageResult} carries text,
     * a session and counters, nothing that says "I am done". Asking for an explicit marker turns
     * the question from guessing at phrasing - which is language- and idiom-bound, and never
     * complete - into reading a declaration. The phrase list below stays as the fallback for a
     * turn that did not declare anything.
     */
    static final String FINAL_MARKER = "<!end>"; //$NON-NLS-1$

    /**
     * How long ONE turn may stay silent before the conversation is wound up.
     *
     * <p>Separate from the job's total budget on purpose: a conversation that stopped moving is
     * done in every sense that matters to the caller, and waiting out the whole budget only
     * delays the answer already in hand. What is NOT done is pretending it finished cleanly -
     * the result says the completion marker never arrived.
     *
     * <p>IDLE, not elapsed: a turn that is working - calling this plugin's tools through the
     * bridge, which reports both started calls and calls still running ({@link BridgeActivity}) -
     * keeps the clock reset. Only silence counts, because a tool loop that legitimately runs for
     * minutes must not be cut off (a project question measured 75 s here, and a bigger project
     * takes longer).
     */
    static final long DEFAULT_IDLE_TURN_TIMEOUT_MS = 120_000L;

    /** How often the wait wakes up to see whether the turn is still doing anything. */
    static final long DEFAULT_IDLE_POLL_MS = 5_000L;

    /** Mutable only so a test can shrink the window; production never changes them. */
    private static volatile long idleTurnTimeoutMs = DEFAULT_IDLE_TURN_TIMEOUT_MS;

    private static volatile long idlePollMs = DEFAULT_IDLE_POLL_MS;

    /**
     * How many Workmate turns this bundle is waiting on right now. The idle rule needs it because
     * the activity it reads is process-wide: see {@link #isOnlyAwaitedTurn()}.
     */
    private static final AtomicInteger AWAITED_TURNS = new AtomicInteger();

    /**
     * Appended by THIS adapter to every request, so the caller's question stays their own and the
     * protocol travels with the conversation rather than with the question.
     */
    private static final String FINALITY_INSTRUCTION =
        " \u041A\u043E\u0433\u0434\u0430 \u043E\u0442\u0432\u0435\u0442 \u043E\u043A\u043E\u043D\u0447\u0430\u0442\u0435\u043B\u044C\u043D\u044B\u0439 \u0438 \u0440\u0430\u0431\u043E\u0442\u0430 " //$NON-NLS-1$
        + "\u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0430, \u0437\u0430\u0432\u0435\u0440\u0448\u0438 \u0435\u0433\u043E \u043E\u0442\u0434\u0435\u043B\u044C\u043D\u043E\u0439 \u043F\u043E\u0441\u043B\u0435\u0434\u043D\u0435\u0439 " //$NON-NLS-1$
        + "\u0441\u0442\u0440\u043E\u043A\u043E\u0439 <!end>. \u041F\u043E\u043A\u0430 \u0440\u0430\u0431\u043E\u0442\u0430 \u043D\u0435 \u0437\u0430\u043A\u043E\u043D\u0447\u0435\u043D\u0430, " //$NON-NLS-1$
        + "\u044D\u0442\u043E\u0442 \u043C\u0430\u0440\u043A\u0435\u0440 \u043D\u0435 \u043F\u0438\u0448\u0438."; //$NON-NLS-1$

    /** The Russian negation particle that turns any of the verbs below into a refusal. */
    private static final String NEGATION_PARTICLE = "\u043D\u0435"; // не

    /** Words that turn a following "not" into a correlative rather than a denial. */
    private static final String[] CORRELATIVE_AFTER_NOT = {
        "only", //$NON-NLS-1$
        "just" //$NON-NLS-1$
    };

    private static final String[] NEGATING_ADVERBS = {
        "not", //$NON-NLS-1$
        "never" //$NON-NLS-1$
    };

    private static final String[] INTENT_MARKERS = {
        "\u0432\u043E\u0441\u043F\u043E\u043B\u044C\u0437\u0443\u044E\u0441\u044C", // воспользуюсь //$NON-NLS-1$
        "\u043F\u043E\u0438\u0449\u0443", // поищу //$NON-NLS-1$
        "\u043D\u0430\u0439\u0434\u0443", // найду //$NON-NLS-1$
        "\u0438\u0437\u0443\u0447\u0443", // изучу //$NON-NLS-1$
        "\u043F\u043E\u0441\u043C\u043E\u0442\u0440\u044E", // посмотрю //$NON-NLS-1$
        "\u043F\u0440\u043E\u0432\u0435\u0440\u044E", // проверю //$NON-NLS-1$
        "\u0441\u043E\u0437\u0434\u0430\u043C", // создам //$NON-NLS-1$
        "\u043D\u0430\u0447\u043D\u0443", // начну //$NON-NLS-1$
        // Analytic future, restricted to an action verb for the same reason "let me" is:
        // the bare auxiliary also opens finished statements ("I will be glad to help").
        "\u0431\u0443\u0434\u0443 \u0438\u0441\u043A\u0430\u0442\u044C", // буду искать //$NON-NLS-1$
        "\u0431\u0443\u0434\u0443 \u043F\u0440\u043E\u0432\u0435\u0440\u044F\u0442\u044C", // буду проверять //$NON-NLS-1$
        "\u0431\u0443\u0434\u0443 \u0441\u043C\u043E\u0442\u0440\u0435\u0442\u044C", // буду смотреть //$NON-NLS-1$
        "\u0431\u0443\u0434\u0443 \u0438\u0437\u0443\u0447\u0430\u0442\u044C", // буду изучать //$NON-NLS-1$
        "\u0431\u0443\u0434\u0443 \u0441\u043E\u0437\u0434\u0430\u0432\u0430\u0442\u044C", // буду создавать //$NON-NLS-1$
        "\u0431\u0443\u0434\u0443 \u0440\u0430\u0437\u0431\u0438\u0440\u0430\u0442\u044C\u0441\u044F", // буду разбираться //$NON-NLS-1$
        "i will", //$NON-NLS-1$
        "i'll", //$NON-NLS-1$
        // "let me" alone is a discourse marker ("let me clarify: ..."), so only the phrases
        // that announce an ACTION are markers of intent.
        "let me search", //$NON-NLS-1$
        "let me check", //$NON-NLS-1$
        "let me look", //$NON-NLS-1$
        "let me find", //$NON-NLS-1$
        "let me run" //$NON-NLS-1$
    };

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

        /**
         * Asks whether the irreversible step may still be taken, and marks it as taken.
         * <p>
         * The caller stops treating a later timeout as a retryable failure - a retry would ask
         * Workmate the same question twice - and a {@code false} answer means the caller has
         * ALREADY published a failure, so the question must not be sent at all.
         *
         * @return {@code true} when the adapter may proceed
         */
        default boolean onTryCommit()
        {
            // Callers that cannot retry anyway have nothing to lose either way.
            return true;
        }
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
        /**
         * The wait ran out AFTER the request had already reached Workmate. Separate from
         * {@link #TIMED_OUT} because the advice differs in the way that matters: Workmate may
         * still be running the request, and its tools change this configuration, so "retry with
         * a larger budget" would run that work a second time.
         */
        TIMED_OUT_AFTER_DISPATCH,
        CALL_FAILED,
        /**
         * Workmate rejected the requested tool NAME before entering anything. Its own message
         * is complete and actionable, so the tool reports it as it stands rather than dressing
         * it in sign-in and network advice that has nothing to do with a mistyped name.
         */
        UNKNOWN_TOOL,
        /**
         * The call failed AFTER the request had reached Workmate. Same reason
         * {@link #TIMED_OUT_AFTER_DISPATCH} exists: that turn had already started and its
         * tools change this configuration, so the advice must not end in "retry".
         */
        FAILED_AFTER_DISPATCH
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

        /** @return timeout of a wait whose request was already sent and cannot be recalled */
        public static GatewayException timedOutAfterDispatch()
        {
            return new GatewayException(FailureKind.TIMED_OUT_AFTER_DISPATCH,
                "the request was sent and the wait for its result timed out"); //$NON-NLS-1$
        }

        public static GatewayException callFailed(String detail)
        {
            return new GatewayException(FailureKind.CALL_FAILED, detail);
        }

        /** @return rejection of a tool NAME, before anything ran */
        public static GatewayException unknownTool(String detail)
        {
            return new GatewayException(FailureKind.UNKNOWN_TOOL, detail);
        }

        /** @return failure of a turn that had already been sent and may have run tools */
        public static GatewayException failedAfterDispatch(String detail)
        {
            return new GatewayException(FailureKind.FAILED_AFTER_DISPATCH, detail);
        }
    }

    /** Immutable response returned to the tool after the reflective call succeeds. */
    public static class WorkmateResponse
    {
        private final String text;
        private final String reasoning;
        private final Integer assistantMessageCount;
        private final int continuations;
        private final boolean declaredFinal;
        private final boolean wentQuiet;
        private final boolean answerAccepted;

        public WorkmateResponse(String text, String reasoning)
        {
            this(text, reasoning, null);
        }

        public WorkmateResponse(String text, String reasoning, Integer assistantMessageCount)
        {
            this(text, reasoning, assistantMessageCount, 0);
        }

        public WorkmateResponse(String text, String reasoning, Integer assistantMessageCount,
            int continuations)
        {
            this(text, reasoning, assistantMessageCount, continuations, true, false);
        }

        public WorkmateResponse(String text, String reasoning, Integer assistantMessageCount,
            int continuations, boolean declaredFinal, boolean wentQuiet)
        {
            this(text, reasoning, assistantMessageCount, continuations, declaredFinal, wentQuiet,
                true);
        }

        public WorkmateResponse(String text, String reasoning, Integer assistantMessageCount, // NOSONAR the outcome needs every one of these to be reported honestly
            int continuations, boolean declaredFinal, boolean wentQuiet, boolean answerAccepted)
        {
            this.text = text;
            this.reasoning = reasoning;
            this.assistantMessageCount = assistantMessageCount;
            this.continuations = continuations;
            this.declaredFinal = declaredFinal;
            this.wentQuiet = wentQuiet;
            this.answerAccepted = answerAccepted;
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

        /**
         * How many times the conversation was pushed to continue before this answer. Zero means
         * the first turn WAS the answer - or that it could not be continued at all, e.g. because
         * the installed Workmate exposes no conversation handle. A caller that phrases "still
         * empty even after continuing" must consult this rather than assume it happened.
         *
         * @return the number of continuations actually sent, never negative
         */
        public int getContinuations()
        {
            return continuations;
        }

        /**
         * Whether Workmate itself marked the answer as final (the agreed end-of-answer marker).
         * When it did not, this text is the last thing it said - which may be complete, but was
         * not declared complete.
         *
         * @return {@code true} when the completion marker arrived
         */
        public boolean isDeclaredFinal()
        {
            return declaredFinal;
        }

        /**
         * Whether the conversation was wound up because a turn stopped answering, rather than
         * because Workmate finished.
         *
         * @return {@code true} when a turn timed out and ended the conversation
         */
        public boolean wentQuiet()
        {
            return wentQuiet;
        }

        /**
         * Whether this text was ever ACCEPTED as an answer. When it was not, it is the last
         * thing Workmate announced it was going to do - kept rather than thrown away because a
         * conversation that stopped mid-work still tells the caller where it stopped, but it is
         * not a result and must never be read as one.
         *
         * @return {@code true} when the text is an accepted answer
         */
        public boolean isAnswerAccepted()
        {
            return answerAccepted;
        }
    }

    /**
     * Sends one new conversation request through Workmate's own full tool loop.
     *
     * @param project optional EDT project; {@code null} selects ProjectId.Default
     * @param question user message
     * @param maxToolRounds optional Workmate tool-round limit
     * @param skillName optional Workmate skill name
     * @param timeoutMillis wall-clock wait bound
     * @return Workmate text and optional reasoning
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
        String skillName, long timeoutMillis) throws GatewayException
    {
        return ask(project, question, maxToolRounds, skillName, timeoutMillis, message -> {
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
     * @param timeoutMillis total remaining job budget used to await Workmate
     * @param progress milestone listener
     * @return Workmate text, optional reasoning and assistant-message count
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
        String skillName, long timeoutMillis, ProgressListener progress) throws GatewayException
    {
        // Taken BEFORE the reflective setup, not after it: bundle lookup, injector resolution
        // and the authorization probe all spend the caller's budget, and a deadline started
        // afterwards would hand the conversation a fresh full one on top of what setup used.
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        // Declared outside the try because the catch at the bottom classifies by it: once a
        // request is out, even an unexpected error must not be answered with a retry.
        AtomicBoolean dispatched = new AtomicBoolean(false);
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
            Class<?> cancellationTokenClass = requireClass(aiBundle, CANCELLATION_TOKEN);
            Method sendAsync = requireMethod(facadeClass, "sendAsync", requestClass, //$NON-NLS-1$
                cancellationTokenClass);
            Class<?> resultClass = requireClass(aiBundle, SEND_RESULT);

            // ONE facade call answers ONE assistant turn: ConversationFacade completes its future
            // when the ask stream ends, and that stream ends on a plan ("I will look it up")
            // exactly as it ends on a finished answer (issue #427). Workmate's own driver does not
            // treat the first turn as the result either - DevAutopilot re-sends into the SAME
            // conversation while the answer still looks like an announcement. So does this loop.
            String effectiveSkill =
                skillName == null || skillName.isEmpty() ? DEFAULT_SKILL : skillName;
            Object session = null;
            String message = question;
            String answer = null;
            String reasoning = null;
            String lastAnnouncement = null;
            boolean declaredFinal = false;
            boolean wentQuiet = false;
            // Nullable on purpose: "the platform did not report a count" is not "zero", and the
            // renderer omits the field for the former. One turn without a count makes the whole
            // aggregate unknown, because a partial sum would be published as if it were the total.
            Integer assistantMessages = Integer.valueOf(0);
            int continuations = 0;
            while (true)
            {
                Object request = create(requestConstructor, projectId,
                    message + FINALITY_INSTRUCTION, session,
                    session == null,
                    // chat = FALSE matches Workmate's OWN default (ConversationFacade maps a null
                    // getChat() to false), and it is NOT what decides whether Workmate works the
                    // task with its tools: with TRUE and with FALSE alike, a "raw" request came
                    // back in ~1.2 s with assistantMessages = 1 and no tool round at all.
                    //
                    // The SKILL is what decides it, measured live against Workmate 1.0.5. Under
                    // ConversationFacade's own default "raw" the cloud answers from the model
                    // alone. Under DEFAULT_SKILL the same facade runs Workmate's full tool loop:
                    // the model called JShellManual, JShellSession and JShell, reached this plugin
                    // through IEdtMcpBridge and answered from real EDT-MCP output (7 assistant
                    // messages). Not every name is accepted - "chat"/"agent"/"git-review" are
                    // refused by the cloud with "Failed to create conversation" in ~35 ms - so do
                    // not treat this as a free-form field.
                    effectiveSkill, Boolean.FALSE, maxToolRounds);

                // Dispatching is irreversible in the way that matters: Workmate's tool loop can
                // edit this configuration, and the cancellation token stops us WAITING, not the
                // edits already made. So the job is committed before the FIRST send - a later
                // "timed out, start a new job" would invite a retry that runs those edits again.
                // The continuations need no second commit: they belong to a job already committed.
                // Checked BEFORE the commit handshake, not only inside the send: a request that
                // has not gone out yet leaves nothing behind, so an expired budget here is an
                // ordinary retryable timeout rather than the "already dispatched" kind.
                if (session == null && remainingMillis(deadlineNanos) <= 0)
                {
                    throw GatewayException.timedOut();
                }
                if (session == null && !progress.onTryCommit())
                {
                    throw GatewayException.callFailed("the job was already reported as finished " //$NON-NLS-1$
                        + "before the question could be sent, so it was not sent"); //$NON-NLS-1$
                }
                Turn turn = sendTurn(facade, sendAsync, request, cancellationTokenClass,
                    resultClass, deadlineNanos, session == null,
                    session == null ? "Sent the request to Workmate." //$NON-NLS-1$
                        : "Asked Workmate to continue in the same conversation.", //$NON-NLS-1$
                    progress, dispatched);
                if (turn == null)
                {
                    wentQuiet = true;
                    break;
                }
                assistantMessages = addMessages(assistantMessages, turn.messages);
                // The DECLARATION wins: a turn that marked itself final is final, whatever it
                // sounds like. Only an undeclared turn is judged by phrasing.
                boolean declared = declaresFinal(turn.text);
                String stripped = stripFinalMarker(turn.text);
                // A marker with no answer in front of it is not an answer: accepting it would
                // report emptiness as a result, or fall back to an earlier announcement.
                declaredFinal = declaredFinal || (declared && stripped != null);
                boolean isAnswer =
                    stripped != null && (declared || !needsContinuation(turn.text));
                // Two DIFFERENT things are remembered, and the difference is the whole point of
                // this loop: an accepted answer, and the last announcement. A later empty turn
                // must not erase an answer already produced - but an announcement must never be
                // promoted to "the answer" just because nothing better followed it, which is the
                // very behaviour issue #427 reported.
                if (isAnswer)
                {
                    answer = stripped;
                    reasoning = turn.reasoning;
                }
                else if (stripped != null)
                {
                    lastAnnouncement = stripped;
                }
                if (isAnswer || turn.session == null || continuations >= MAX_CONTINUATIONS
                    || remainingMillis(deadlineNanos) <= 0)
                {
                    break;
                }
                session = turn.session;
                message = CONTINUATION_PROMPT;
                continuations++;
                progress.onProgress("Workmate answered with an intention rather than a result; " //$NON-NLS-1$
                    + "continuing the same conversation (" + continuations + " of " //$NON-NLS-1$ //$NON-NLS-2$
                    + MAX_CONTINUATIONS + ")."); //$NON-NLS-1$
            }
            if (answer == null && lastAnnouncement != null && !wentQuiet)
            {
                // Workmate said something every time and never finished. Reporting its last
                // announcement as the answer would be exactly the #427 behaviour; reporting
                // "empty" would be untrue. So the call fails, quoting what it kept saying.
                throw GatewayException.failedAfterDispatch("1C:Workmate never produced a final answer: " //$NON-NLS-1$
                    + "after " + continuations + " continuation(s) - each of which had already " //$NON-NLS-1$ //$NON-NLS-2$
                    + "run, possibly through its tools, so inspect Workmate and the project " //$NON-NLS-1$
                    + "before repeating the request - it was still announcing what " //$NON-NLS-1$
                    + "it intended to do (\"" + summarize(lastAnnouncement) + "\"). Ask a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "narrower question, or raise timeoutSeconds so its tool loop can finish."); //$NON-NLS-1$
            }
            // Quiet or exhausted, the caller still gets what Workmate produced - with the fact
            // that it never said it was finished, which is the difference between an answer and
            // the last thing it happened to say.
            String reported = answer != null ? answer : stripFinalMarker(lastAnnouncement);
            progress.onProgress(declaredFinal ? "Received the Workmate response." //$NON-NLS-1$
                : "Received a response that Workmate did not mark as final."); //$NON-NLS-1$
            return new WorkmateResponse(reported, reasoning, assistantMessages, continuations,
                declaredFinal, wentQuiet, answer != null);
        }
        catch (GatewayException e)
        {
            throw e;
        }
        catch (RuntimeException | LinkageError e)
        {
            throw dispatchedOrPlainFailure(dispatched.get(), rootCauseMessage(e));
        }
    }

    /**
     * The right failure for something that escaped an outer catch: after a request has gone
     * out, even an unexpected error must not be answered with a retry.
     *
     * @param dispatched whether a request had already been sent
     * @param detail what went wrong
     * @return the failure to throw
     */
    private static GatewayException dispatchedOrPlainFailure(boolean dispatched, String detail)
    {
        return dispatched ? GatewayException.failedAfterDispatch(detail)
            : GatewayException.callFailed(detail);
    }

    /**
     * Whether {@link #FINAL_MARKER} sits at {@code at}, compared case-insensitively and without
     * copying or re-casing the text.
     *
     * @param text the text being cleaned
     * @param at the candidate offset
     * @return {@code true} when the marker starts there
     */
    private static boolean regionMatchesIgnoreCase(CharSequence text, int at)
    {
        for (int i = 0; i < FINAL_MARKER.length(); i++)
        {
            if (Character.toLowerCase(text.charAt(at + i)) != FINAL_MARKER.charAt(i))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the turn declared itself final by carrying {@link #FINAL_MARKER}.
     *
     * @param text the turn's text (may be {@code null})
     * @return {@code true} when the marker is present
     */
    static boolean declaresFinal(String text)
    {
        // ENDS with, not contains: the instruction asks for the marker as the last line, and a
        // turn that merely mentions it - explaining the protocol, quoting an earlier answer -
        // has not declared anything.
        return text != null && text.trim().toLowerCase(Locale.ROOT).endsWith(FINAL_MARKER);
    }

    /**
     * The answer without the protocol marker: it is this adapter's bookkeeping, not something the
     * caller asked for.
     *
     * @param text the turn's text (may be {@code null})
     * @return the text with every occurrence of the marker removed and trimmed
     */
    static String stripFinalMarker(String text)
    {
        if (text == null)
        {
            return null;
        }
        // Only the TRAILING marker is protocol. One written inside the text is the model's own
        // words - explaining the protocol, quoting an earlier answer - and cutting it would edit
        // the answer this adapter is supposed to pass through.
        //
        // Case-insensitive matching WITHOUT lowercasing the text: a character whose lowercase
        // mapping is longer (U+0130, say) shifts every later index, and deleting by an index taken
        // from the lowercased copy would then cut the answer instead of the marker.
        StringBuilder cleaned = new StringBuilder(text);
        trimEnd(cleaned);
        while (cleaned.length() >= FINAL_MARKER.length()
            && regionMatchesIgnoreCase(cleaned, cleaned.length() - FINAL_MARKER.length()))
        {
            cleaned.setLength(cleaned.length() - FINAL_MARKER.length());
            trimEnd(cleaned);
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Drops trailing whitespace from {@code text} in place.
     *
     * @param text the buffer being cleaned
     */
    private static void trimEnd(StringBuilder text)
    {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
        {
            end--;
        }
        text.setLength(end);
    }

    /**
     * Whether Workmate's answer is an announcement of what it is ABOUT to do rather than the
     * result, and the conversation should therefore be pushed to continue.
     *
     * <p>Two shapes, both reported by issue #427 and both produced by the platform completing its
     * ask stream after one assistant turn:
     * <ul>
     *   <li>an EMPTY answer — nothing was said at all, so there is nothing to report;</li>
     *   <li>a SHORT answer that states an intention ("For a full reference \u2026 I will use the 1C
     *       documentation search"). Length is what keeps this from eating real answers: a
     *       finished answer that happens to contain such a word is not {@value
     *       #PLAN_TEXT_MAX_CHARS} characters short.</li>
     * </ul>
     * Over-eagerness here costs one extra round-trip and nothing else — the continuation cannot
     * lose an answer, because the last NON-BLANK text is what the caller receives. Under-eagerness
     * is the bug itself.
     *
     * @param text Workmate's answer for this turn (may be {@code null})
     * @return {@code true} when the conversation should be continued
     */
    static boolean needsContinuation(String text)
    {
        String trimmed = trimToNull(text);
        if (trimmed == null)
        {
            return true;
        }
        if (trimmed.length() > PLAN_TEXT_MAX_CHARS)
        {
            return false;
        }
        // Apostrophes are normalized first: the markers are written with the ASCII one.
        String lower = normalizeApostrophes(trimmed.toLowerCase(Locale.ROOT));
        for (String marker : INTENT_MARKERS)
        {
            if (announcesAction(lower, marker))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * First line (or first 160 characters) of a message, for quoting inside an error.
     *
     * @param text the message (never {@code null})
     * @return a single-line excerpt
     */
    private static String summarize(String text)
    {
        String oneLine = text.trim().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "..."; //$NON-NLS-1$
    }

    /**
     * Whether one of {@code words} begins at the first non-space position at or after
     * {@code from}, as a whole word.
     *
     * @param text the lowercased answer
     * @param from where to start looking
     * @param words the candidate words
     * @return {@code true} when one of them is the next word
     */
    private static boolean startsWord(String text, int from, String[] words)
    {
        int start = skipWhitespaceForward(text, from);
        for (String word : words)
        {
            int end = start + word.length();
            if (text.startsWith(word, start)
                && (end >= text.length() || !Character.isLetter(text.charAt(end))))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The first non-whitespace position at or after {@code from}.
     *
     * <p>ALL whitespace, not the space character: a model wraps its lines where it likes, and
     * "I will\nnot edit generated files" must read as the refusal it is rather than as an
     * announcement whose negation happens to sit on the next line.
     *
     * @param text the lowercased answer
     * @param from where to start
     * @return the index of the next non-whitespace character, or the text length
     */
    private static int skipWhitespaceForward(String text, int from)
    {
        int start = from;
        while (start < text.length() && Character.isWhitespace(text.charAt(start)))
        {
            start++;
        }
        return start;
    }

    /**
     * The position just past the last non-whitespace character before {@code at}.
     *
     * @param text the lowercased answer
     * @param at where to start looking back from
     * @return the index just after the preceding word, or {@code 0}
     */
    private static int skipWhitespaceBackward(String text, int at)
    {
        int end = at;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
        {
            end--;
        }
        return end;
    }

    /**
     * Replaces typographic apostrophes with the ASCII one.
     *
     * <p>Models punctuate contractions with U+2019 ("I\u2019ll"), while the markers are
     * written with the plain apostrophe. Normalizing the TEXT keeps one spelling per marker.
     *
     * @param text the lowercased answer
     * @return the same text with apostrophe variants unified
     */
    private static String normalizeApostrophes(String text)
    {
        return text.replace('\u2019', '\'').replace('\u02BC', '\'');
    }

    /**
     * Whether {@code text} contains {@code marker} as a whole word.
     *
     * <p>A plain {@code contains} with a trailing space in the marker misses every announcement
     * the model punctuates - "I will:", "I'll." or a bullet list opener - which is the very shape
     * this predicate exists to catch. Requiring a NON-LETTER (or the end of the text) after the
     * marker accepts those and still refuses a longer word that merely starts the same way.
     *
     * @param text the lowercased answer
     * @param marker the lowercased marker
     * @return {@code true} when the marker appears as a whole word
     */
    private static boolean announcesAction(String text, String marker)
    {
        int from = 0;
        while (from <= text.length() - marker.length())
        {
            int at = text.indexOf(marker, from);
            if (at < 0)
            {
                return false;
            }
            int after = at + marker.length();
            boolean wholeWord = (at == 0 || !Character.isLetter(text.charAt(at - 1)))
                && (after >= text.length() || !Character.isLetter(text.charAt(after)));
            // Negation is judged per OCCURRENCE, not per text: "I will not edit generated files;
            // I will inspect the source model" refuses one thing and announces another, and the
            // announcement is what decides whether the turn is finished.
            if (wholeWord && !isNegated(text, at, after))
            {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    /**
     * Whether the marker occurrence at {@code at} is directly negated, as in "\u043D\u0435
     * \u043F\u0440\u043E\u0432\u0435\u0440\u044E" ("I will not check").
     *
     * <p>Russian builds the negated future by putting "\u043D\u0435" in front of the very verb
     * this list matches, so without this the refusal reads as the announcement it denies.
     *
     * @param text the lowercased answer
     * @param at where the marker starts
     * @return {@code true} when a negation particle immediately precedes it
     */
    private static boolean isNegated(String text, int at, int after)
    {
        return negatedBefore(text, at) || negatedAfter(text, after);
    }

    /**
     * Russian negation: the particle sits in front of the verb this list matches
     * ("не проверю").
     *
     * @param text the lowercased answer
     * @param at where the marker starts
     * @return {@code true} when the particle immediately precedes it
     */
    private static boolean negatedBefore(String text, int at)
    {
        int end = skipWhitespaceBackward(text, at);
        return end >= NEGATION_PARTICLE.length()
            && text.startsWith(NEGATION_PARTICLE, end - NEGATION_PARTICLE.length())
            && (end == NEGATION_PARTICLE.length()
                || !Character.isLetter(text.charAt(end - NEGATION_PARTICLE.length() - 1)));
    }

    /**
     * English negation: the adverb follows the auxiliary ("I will NOT edit", "I'll NEVER touch").
     *
     * @param text the lowercased answer
     * @param after the index just past the marker
     * @return {@code true} when a negating adverb follows it
     */
    private static boolean negatedAfter(String text, int after)
    {
        int start = skipWhitespaceForward(text, after);
        for (String adverb : NEGATING_ADVERBS)
        {
            int end = start + adverb.length();
            if (text.startsWith(adverb, start)
                && (end >= text.length() || !Character.isLetter(text.charAt(end))))
            {
                // "I will NOT ONLY inspect the module, I will also fix it" denies nothing - the
                // correlative intensifies the announcement that follows it.
                return !startsWord(text, end, CORRELATIVE_AFTER_NOT);
            }
        }
        return false;
    }

    /**
     * Sends one prepared request and reads the assistant turn it produced.
     *
     * @param facade the conversation facade
     * @param sendAsync its {@code sendAsync} method
     * @param request the prepared {@code SendUserMessageRequest}
     * @param cancellationTokenClass the token interface to implement for this send
     * @param resultClass the {@code SendMessageResult} class to read the turn from
     * @param deadlineNanos when the caller's total budget expires
     * @param firstTurn whether this is the first send of the conversation
     * @param sentMessage the progress milestone to report once the request is away
     * @param progress milestone listener
     * @return the turn Workmate answered with
     * @throws GatewayException categorized runtime/compatibility failure
     */
    private Turn sendTurn(Object facade, Method sendAsync, Object request, // NOSONAR one reflective send needs every piece of the reflective context
        Class<?> cancellationTokenClass, Class<?> resultClass, long deadlineNanos,
        boolean firstTurn, String sentMessage, ProgressListener progress, AtomicBoolean dispatched)
        throws GatewayException
    {
        // Immediately before the send, because dispatching is what has consequences: Workmate's
        // tool loop can change this configuration, and a request let out after the advertised
        // budget spends time the caller was never promised. A later wait-timeout does not undo it.
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis <= 0)
        {
            throw firstTurn ? GatewayException.timedOut() : GatewayException.timedOutAfterDispatch();
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Object cancellationToken = createCancellationToken(cancellationTokenClass, cancelled);
        Object futureValue;
        try
        {
            futureValue = invoke(sendAsync, facade, request, cancellationToken);
        }
        catch (GatewayException e)
        {
            // sendAsync can also fail BEFORE it returns a future. On a continuation the earlier
            // turns have already run, so this failure carries the same warning as a later one.
            throw firstTurn ? e : continuationFailed(e.getDetail());
        }
        if (!(futureValue instanceof CompletableFuture<?>))
        {
            throw GatewayException.incompatible("method '" + CONVERSATION_FACADE //$NON-NLS-1$
                + ".sendAsync' returned " + typeName(futureValue) //$NON-NLS-1$
                + " instead of CompletableFuture"); //$NON-NLS-1$
        }
        // Raised HERE, not by the caller: everything from this point on runs with a request
        // that is already out, so a failure escaping this method - even one this method does
        // not classify itself - must not be answered with a retry.
        dispatched.set(true);
        progress.onProgress(sentMessage);

        // Recomputed AFTER the dispatch returned: sendAsync does synchronous work of its own
        // (it creates the conversation), and waiting on the budget measured before it would add
        // that duration back on top of the absolute deadline, once per turn.
        long waitMillis = remainingMillis(deadlineNanos);
        if (waitMillis <= 0)
        {
            // The request is already out, so this is never the retryable kind of timeout.
            cancelled.set(true);
            ((CompletableFuture<?>)futureValue).cancel(true);
            throw GatewayException.timedOutAfterDispatch();
        }

        Object sendResult;
        CompletableFuture<?> future = (CompletableFuture<?>)futureValue;
        try
        {
            // Milliseconds, not floored seconds: rounding down cancelled a turn up to a second
            // before the advertised budget ran out, and a floor of one second let an already
            // spent budget overshoot by one more.
            sendResult = awaitTurn(future, waitMillis);
        }
        catch (TimeoutException e)
        {
            boolean idleBound = e instanceof IdleTimeoutException;
            // The token ASKS Workmate to stop; it does not undo what its tools have
            // already done. So this is a dispatched timeout, not a plain retryable one -
            // the difference is whether the caller may safely run the same request again.
            cancelled.set(true);
            future.cancel(true);
            if (idleBound)
            {
                // The TURN went quiet rather than the budget running out: wind the conversation
                // up with what is already in hand, and let the caller see that Workmate never
                // declared itself finished.
                progress.onProgress("No sign of work for " + (idleTurnTimeoutMs / 1000) //$NON-NLS-1$
                    + "s - no calls into this plugin, none running - so the conversation was " //$NON-NLS-1$
                    + "wound up without a completion marker."); //$NON-NLS-1$
                return null;
            }
            throw GatewayException.timedOutAfterDispatch();
        }
        catch (InterruptedException e)
        {
            cancelled.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw GatewayException.failedAfterDispatch("the waiting thread was interrupted " //$NON-NLS-1$
                + "while the turn was already running."); //$NON-NLS-1$
        }
        catch (ExecutionException e)
        {
            // The turn ran and then failed - on the FIRST send as much as on a continuation. Its
            // tools may already have changed the project, so neither may invite a blind retry.
            throw dispatchedFailed(firstTurn, rootCauseMessage(e));
        }

        if (sendResult == null)
        {
            throw dispatchedFailed(firstTurn, "sendAsync completed without a result"); //$NON-NLS-1$
        }
        String text;
        String reasoning;
        Integer count;
        try
        {
            text = stringValue(invoke(requireMethod(resultClass, "getText"), sendResult)); //$NON-NLS-1$
            reasoning = stringValue(invoke(requireMethod(resultClass, "getReasoning"), sendResult)); //$NON-NLS-1$
            // null BEFORE integerValue: that helper rejects every non-Number, null included, so
            // converting first would turn "the platform reported no count" into a failed job.
            Object rawCount =
                invoke(requireMethod(resultClass, "getAssistantMessageCount"), sendResult); //$NON-NLS-1$
            count = rawCount == null ? null : integerValue(rawCount);
        }
        catch (GatewayException e)
        {
            // An answer that cannot be READ is still an answer that was produced: the same reason
            // the conversation-handle failure below carries the inspect-first warning.
            throw dispatchedFailed(firstTurn,
                "its answer could not be read - " + e.getDetail()); //$NON-NLS-1$
        }
        Object session;
        try
        {
            session = sessionOf(resultClass, sendResult);
        }
        catch (GatewayException e)
        {
            // This turn has ALREADY run - possibly through Workmate's tools, which change this
            // configuration. A bare "call failed" would be answered with a retry that performs
            // the same work again, so the message says what happened and what to check first.
            throw GatewayException.failedAfterDispatch("1C:Workmate answered, but its conversation handle "
                + "could not be read (" + e.getDetail() + "), so the conversation cannot be "
                + "continued. That answer was already produced and its tools may have run: "
                + "inspect Workmate and the project before starting the same request again."); //$NON-NLS-1$
        }
        return new Turn(text, reasoning, count, session);
    }

    /**
     * A failure that happened AFTER the request went out, phrased so it is not answered with a
     * blind retry: the turn had already started, and Workmate runs its tools inside it.
     *
     * @param firstTurn whether this was the conversation's first send
     * @param detail what went wrong
     * @return the failure to throw
     */
    private static GatewayException dispatchedFailed(boolean firstTurn, String detail)
    {
        if (!firstTurn)
        {
            return continuationFailed(detail);
        }
        return GatewayException.failedAfterDispatch("1C:Workmate failed after its turn had " //$NON-NLS-1$
            + "already started (" + detail + "). That turn was running Workmate's tools, which " //$NON-NLS-1$ //$NON-NLS-2$
            + "change this project: inspect Workmate and the project before repeating the " //$NON-NLS-1$
            + "request."); //$NON-NLS-1$
    }

    /**
     * A failure of a CONTINUATION, phrased so it is not answered with a blind retry: the turns
     * before it have already run, possibly through Workmate's tools.
     *
     * @param detail what went wrong
     * @return the failure to throw
     */
    private static GatewayException continuationFailed(String detail)
    {
        return GatewayException.failedAfterDispatch("1C:Workmate failed while continuing the " //$NON-NLS-1$
            + "conversation (" + detail + "). Earlier turns had already run and their tools may " //$NON-NLS-1$ //$NON-NLS-2$
            + "have changed the project: inspect Workmate and the project before repeating the " //$NON-NLS-1$
            + "request."); //$NON-NLS-1$
    }

    /**
     * Waits for the turn, giving up early only when it has gone SILENT.
     *
     * <p>Activity is the calls Workmate makes back into this plugin, read from
     * {@link BridgeActivity}: calls STARTED since the last look, plus calls still executing. Both
     * are needed - a single tool that runs for minutes starts once and would otherwise read as
     * silence - and neither can stand still while work goes on, which is why the retained call
     * history is not the signal (it is bounded, and it can be switched off).
     *
     * @param future the turn's future
     * @param budgetMillis what is left of the caller's total budget
     * @return the turn's result
     * @throws IdleTimeoutException when nothing happened for the idle window
     * @throws TimeoutException when the caller's budget ran out first
     * @throws InterruptedException if the wait is interrupted
     * @throws ExecutionException if the turn failed
     */
    static Object awaitTurn(CompletableFuture<?> future, long budgetMillis)
        throws TimeoutException, InterruptedException, ExecutionException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        long lastActivity = System.nanoTime();
        long seenCalls = BridgeActivity.ticks();
        AWAITED_TURNS.incrementAndGet();
        try
        {
            while (true)
            {
                long leftMs =
                    Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (leftMs <= 0)
                {
                    throw new TimeoutException("the job's budget ran out"); //$NON-NLS-1$
                }
                try
                {
                    return future.get(Math.min(leftMs, idlePollMs), TimeUnit.MILLISECONDS);
                }
                catch (TimeoutException stillRunning)
                {
                    long calls = BridgeActivity.ticks();
                    // Either signal means "alive": a new call since the last look, or one that is
                    // still running now. Without the second, a single long tool call - the very
                    // case this timeout must not interrupt - would look like silence after its
                    // one tick.
                    //
                    // The third case is not evidence at all: while a second turn is awaited the
                    // signal cannot be attributed, so the clock does not RUN rather than merely
                    // not firing. Without this reset, the silence measured during the ambiguous
                    // stretch would be spent the instant the other turn ended, and the survivor
                    // would be cut without ever having been watched alone for a full window.
                    if (calls != seenCalls || BridgeActivity.inFlight() > 0
                        || !isOnlyAwaitedTurn())
                    {
                        seenCalls = calls;
                        lastActivity = System.nanoTime();
                    }
                    else if (System.nanoTime() - lastActivity
                        >= TimeUnit.MILLISECONDS.toNanos(idleTurnTimeoutMs))
                    {
                        throw new IdleTimeoutException();
                    }
                }
            }
        }
        finally
        {
            AWAITED_TURNS.decrementAndGet();
        }
    }

    /**
     * Whether this is the only Workmate turn being awaited right now.
     *
     * <p>The bridge counters are process-wide, and Workmate's API offers nothing to attribute a
     * call to a conversation: {@code IEdtMcpBridge.callTool} carries a tool name and arguments,
     * nothing else. So while two jobs run at once, activity proves only that SOMETHING is
     * working - it cannot prove that THIS turn is. Cutting a turn on that evidence would end a
     * live conversation because a different one went quiet, so the idle rule stands down and the
     * job's own budget is the bound. With one job - the ordinary case - nothing changes.
     *
     * @return {@code true} when exactly one turn is waiting on Workmate
     */
    private static boolean isOnlyAwaitedTurn()
    {
        return AWAITED_TURNS.get() <= 1;
    }

    /**
     * Whether this timeout means "the turn went silent" rather than "the budget ran out".
     *
     * @param e the timeout that ended a wait
     * @return {@code true} for the idle kind
     */
    static boolean isIdleTimeout(TimeoutException e)
    {
        return e instanceof IdleTimeoutException;
    }

    /**
     * @return how many Workmate turns are being awaited right now
     */
    static int awaitedTurns()
    {
        return AWAITED_TURNS.get();
    }

    /** Test seam: shrinks the idle window so a test does not wait two minutes. */
    static void setIdleTimingsForTest(long timeoutMs, long pollMs)
    {
        idleTurnTimeoutMs = timeoutMs;
        idlePollMs = pollMs;
    }

    /** Test seam: restores the production idle window. */
    static void resetIdleTimingsForTest()
    {
        idleTurnTimeoutMs = DEFAULT_IDLE_TURN_TIMEOUT_MS;
        idlePollMs = DEFAULT_IDLE_POLL_MS;
    }

    /** A turn that stopped doing anything, as opposed to one that ran out of budget. */
    private static final class IdleTimeoutException extends TimeoutException
    {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The conversation handle this turn belongs to, or {@code null} when the installed Workmate
     * does not expose one. A missing handle is not a failure: it only means this turn cannot be
     * continued, so the answer is reported as it stands rather than the whole call failing.
     *
     * @param resultClass the {@code SendMessageResult} class
     * @param sendResult the result instance
     * @return the {@code ConversationSession}, or {@code null}
     */
    private static Object sessionOf(Class<?> resultClass, Object sendResult)
        throws GatewayException
    {
        Method getSession;
        try
        {
            getSession = resultClass.getMethod("getSession"); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | SecurityException e) // NOSONAR an ABSENT method only costs the continuation
        {
            return null;
        }
        // Only the LOOKUP is optional. A getter that exists and throws is a real Workmate failure,
        // and swallowing it here would quietly turn a broken conversation into "cannot continue",
        // reporting a plan as the answer.
        return invoke(getSession, sendResult);
    }

    /**
     * Adds one turn's assistant-message count to the running total, keeping "unknown" unknown.
     *
     * @param total the total so far, or {@code null} once any turn failed to report one
     * @param turnCount this turn's count, or {@code null} when the platform did not report it
     * @return the new total, or {@code null}
     */
    private static Integer addMessages(Integer total, Integer turnCount)
    {
        if (total == null || turnCount == null)
        {
            return null;
        }
        return Integer.valueOf(total.intValue() + turnCount.intValue());
    }

    /**
     * Milliseconds left of a total budget, never negative.
     *
     * @param deadlineNanos the {@link System#nanoTime()} value the budget expires at
     * @return remaining whole milliseconds
     */
    private static long remainingMillis(long deadlineNanos)
    {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /** {@code null} for a {@code null}/blank string, the trimmed value otherwise. */
    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** One assistant turn: what it said, how many messages it took, and how to continue it. */
    private static final class Turn
    {
        private final String text;
        private final String reasoning;
        private final Integer messages;
        private final Object session;

        Turn(String text, String reasoning, Integer messages, Object session)
        {
            this.text = text;
            this.reasoning = reasoning;
            this.messages = messages;
            this.session = session;
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
     * @param timeoutMillis how long to wait for the tool
     * @param progress milestone listener
     * @return the tool's own textual result
     * @throws GatewayException categorized runtime/compatibility failure
     */
    public String callWorkmateTool(String toolName, String argsJson, long timeoutMillis,
        ProgressListener progress) throws GatewayException
    {
        // Outside the try, because the catch at the bottom classifies by it: a tool that has
        // started may already have run code, and "retry" would run it twice.
        AtomicBoolean invoked = new AtomicBoolean(false);
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

            // Same reason as the facade above: a Workmate tool can run arbitrary code (JShell)
            // or change the configuration, and cancelling the wait does not undo that.
            if (!progress.onTryCommit())
            {
                throw GatewayException.callFailed("the job was already reported as finished " //$NON-NLS-1$
                    + "before tool '" + toolName + "' could be invoked, so it was not invoked"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Object futureValue = invoke(callTools, tools, calls, token);
            // The tool is RUNNING from here on - JShell executes arbitrary code, and other
            // Workmate tools change this project - so nothing below may advise a retry.
            invoked.set(true);
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
                result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e)
            {
                // The token ASKS Workmate to stop; it does not undo what its tools have
                // already done. So this is a dispatched timeout, not a plain retryable one -
                // the difference is whether the caller may safely run the same request again.
                cancelled.set(true);
                future.cancel(true);
                throw GatewayException.timedOutAfterDispatch();
            }
            catch (InterruptedException e)
            {
                cancelled.set(true);
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw GatewayException.failedAfterDispatch("the waiting thread was " //$NON-NLS-1$
                    + "interrupted while tool '" + toolName + "' was already running."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (ExecutionException e)
            {
                throw GatewayException.failedAfterDispatch("Workmate tool '" + toolName //$NON-NLS-1$
                    + "' failed after it had been invoked (" //$NON-NLS-1$
                    + rootCauseMessage(e.getCause() == null ? e : e.getCause())
                    + "). It may already have changed this project."); //$NON-NLS-1$
            }
            progress.onProgress("Workmate tool '" + toolName + "' returned."); //$NON-NLS-1$ //$NON-NLS-2$
            // BEFORE the post-dispatch wrapper below, because a name Workmate does not know is
            // rejected by its dispatch loop without any tool being entered: that call is safe to
            // correct and repeat, and warning "the project may have changed" would be a lie.
            rejectUnknownTool(result, toolName);
            try
            {
                return extractToolText(result, toolName);
            }
            catch (GatewayException e)
            {
                // The tool RAN; only its answer could not be read. Reporting that as an ordinary
                // failure would advertise a retry, and this tool may have executed code.
                throw GatewayException.failedAfterDispatch("Workmate tool '" + toolName //$NON-NLS-1$
                    + "' ran, but its result could not be read - " + e.getDetail()); //$NON-NLS-1$
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            throw dispatchedOrPlainFailure(invoked.get(), rootCauseMessage(e));
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

            // Touch the id Workmate generated, so idle expiry does not drop THAT entry: the
            // session answers to two keys, and evicting either one runs Workmate's removal
            // listener, which CLOSES the shared session object.
            String generated = generatedSessionId;
            if (generated != null)
            {
                invoke(getSession, manager, generated);
            }

            Object existing = invoke(getSession, manager, CHAT_SESSION_ID);
            if (existing != null && !isClosedSession(existing))
            {
                return CHAT_SESSION_ID;
            }
            // A present but CLOSED session is the case a plain null-check misses: the entry
            // still resolves, every JShell call against it fails, and nothing recreates it.

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
            // Workmate's 16, and both keys are kept warm on every pass above.
            invoke(put, cache, CHAT_SESSION_ID, session);
            generatedSessionId = readSessionId(session);

            // Publishing a CLOSED session would be worse than publishing none: the entry
            // resolves, so the next pass would accept it and every JShell call against it
            // would fail. A concurrent creation can evict - and therefore close - this very
            // session between its creation and this line, so the object itself is checked,
            // not merely the presence of the key.
            if (isClosedSession(session))
            {
                throw GatewayException.notReady("Workmate closed the new JShell session " //$NON-NLS-1$
                    + "before it could be published (its session cache evicted it)"); //$NON-NLS-1$
            }

            // NOT_READY, not INCOMPATIBLE: the entry can also be missing because a concurrent
            // creation evicted it in the moment between the put above and this lookup, and
            // that race resolves itself on the next pass. INCOMPATIBLE would stop the
            // publisher for good, so the transient cause must not be able to reach it.
            if (invoke(getSession, manager, CHAT_SESSION_ID) == null)
            {
                throw GatewayException.notReady("the session did not become reachable " //$NON-NLS-1$
                    + "under id '" + CHAT_SESSION_ID //$NON-NLS-1$
                    + "' (its session cache evicted the entry)"); //$NON-NLS-1$
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

    /**
     * Returns the running workbench's display, or {@code null} when there is none.
     * <p>
     * Deliberately not {@code Display.getDefault()}: that CREATES a display when none exists,
     * and on a background worker during shutdown the created one has no event loop, so anything
     * posted to it waits forever.
     *
     * @return a live workbench display, or {@code null}
     */
    private static Display workbenchDisplay()
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
            {
                return null;
            }
            Display display = PlatformUI.getWorkbench().getDisplay();
            return display == null || display.isDisposed() ? null : display;
        }
        catch (RuntimeException e)
        {
            // A workbench that is tearing down can throw rather than answer.
            return null;
        }
    }

    /**
     * Reports whether a Workmate JShell session has already been closed.
     * <p>
     * Tolerant on purpose: when the field cannot be read the session is treated as ALIVE,
     * because the alternative - recreating on every pass - would churn Workmate's session
     * cache instead of merely leaving the chat without a bridge.
     *
     * @param session the session object to inspect
     * @return {@code true} only when the session is provably closed
     */
    private static boolean isClosedSession(Object session)
    {
        try
        {
            Field closedField = session.getClass().getDeclaredField("isClosed"); //$NON-NLS-1$
            closedField.setAccessible(true);
            Object closed = closedField.get(session);
            return closed instanceof AtomicBoolean && ((AtomicBoolean)closed).get();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Reads the id Workmate assigned to a session, so both of its cache keys can be kept
     * warm. Returns {@code null} when unavailable; the caller then simply cannot touch it.
     *
     * @param session the session object to inspect
     * @return the session's own id, or {@code null}
     */
    private static String readSessionId(Object session)
    {
        try
        {
            Object id = session.getClass().getMethod("getSessionId").invoke(session); //$NON-NLS-1$
            return id instanceof String ? (String)id : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Drops the session published under {@link #CHAT_SESSION_ID}, so nothing outlives this
     * bundle.
     * <p>
     * A JShell session keeps whatever the snippets bound in it - including {@code mcp}, the
     * bridge object of the bundle that is stopping, along with its class loader. Left alive
     * across an update, the chat would go on calling the OLD bridge: unregistering an OSGi
     * service does not revoke a reference already handed out. Invalidating runs Workmate's
     * removal listener, which closes the session, so the next start publishes a fresh one
     * bound to the new bridge.
     * <p>
     * Best effort and silent: this runs during teardown, where nothing can be reported and
     * a missing or already-stopped Workmate is the normal case.
     */
    public void discardChatSession()
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
                return;
            }
            Method invalidate =
                requireMethod(managerClass, "invalidateSession", String.class); //$NON-NLS-1$
            // Both keys: the constant one, and the id Workmate generated for the same
            // object, so its cache is not left holding an entry for a closed session.
            invoke(invalidate, manager, CHAT_SESSION_ID);
            String generated = generatedSessionId;
            if (generated != null)
            {
                invoke(invalidate, manager, generated);
                generatedSessionId = null;
            }
        }
        catch (GatewayException | RuntimeException e)
        {
            // Teardown: nothing to report to, and Workmate being gone is expected.
        }
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
     * <p>
     * CALL IT FROM A CANCELLABLE BACKGROUND JOB, never from an MCP request thread. Once the SWT
     * hand-off is claimed this waits for the chat view without a bound of its own: only the
     * runnable knows whether the question was actually sent, and guessing either way is worse
     * than waiting (a guessed timeout invites a duplicate question, a guessed success can be a
     * lie). The caller's cancellation - the job deadline - is what ends the wait.
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
            //
            // The workbench's display, and never Display.getDefault(): this runs on a background
            // worker, and getDefault() CREATES a display when none exists. During shutdown that
            // would build a fresh display on the worker thread with no event loop behind it, so
            // the asyncExec below would never run and the hand-off would hang until its timeout.
            Display display = workbenchDisplay();
            if (display == null)
            {
                throw GatewayException.notReady("the EDT workbench UI is gone (EDT is closing " //$NON-NLS-1$
                    + "or running headless), so the chat view cannot be opened"); //$NON-NLS-1$
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(1);
            // ONE claim decides whether the question is asked, taken by whoever gets there
            // first: the runnable before invoking, or this thread when it gives up. A
            // queued asyncExec is NOT cancelled by our giving up on it, and a plain
            // read-then-set flag still loses the race at the boundary - the runnable can
            // read "not abandoned", this thread can then time out, and the question is
            // asked anyway, after the caller was told it failed and possibly retried.
            AtomicBoolean claimed = new AtomicBoolean(false);
            display.asyncExec(() -> {
                if (!claimed.compareAndSet(false, true))
                {
                    return;
                }
                try
                {
                    // The point of no return, taken from HERE rather than from the waiting
                    // thread: the waiter only learns that it lost the claim when its wait runs
                    // out, and the job's own budget can expire long before that - exactly when
                    // a manufactured "timed out, start a new job" would send this question to
                    // Workmate a second time. A refusal means the caller already published a
                    // failure, so the question must not be asked at all.
                    if (!progress.onTryCommit())
                    {
                        return;
                    }
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
            try
            {
                if (!delivered.await(CHAT_HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    && claimed.compareAndSet(false, true))
                {
                    throw GatewayException.timedOut();
                }
                // Losing the claim here means the runnable started first, and only the runnable
                // knows how this ends: it either asks the question or finds the job already
                // terminal and skips it. Reporting either outcome before it decides would be a
                // guess - a timeout invites a duplicate question, a success can be a lie - so
                // this waits for the runnable itself. The wait is bounded by the job's own
                // budget, which interrupts this thread when it expires.
                delivered.await();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                if (!claimed.compareAndSet(false, true))
                {
                    // The runnable owns delivery. An interrupt here means the job was already
                    // published as terminal - that is the only thing that interrupts this
                    // thread - so the runnable's own commit check will refuse and the question
                    // will NOT be sent. Claiming delivery would be a lie, and claiming a
                    // failure changes nothing about a job that is already terminal.
                    throw GatewayException.callFailed("the wait for the Workmate chat view was " //$NON-NLS-1$
                        + "cut short before it confirmed the hand-off"); //$NON-NLS-1$
                }
                throw e;
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
    /**
     * Fails RETRYABLY when Workmate rejected the call because it knows no tool by that name.
     *
     * <p>Its dispatch loop looks the name up before entering anything: an unknown one is put
     * aside in {@code unknownCalls} and the result completes normally with no messages at all.
     * Told apart from a tool that ran and answered badly, this is the difference between "fix
     * the name and call again" and "something may have changed, look first".
     *
     * <p>Tolerant by design: a Workmate build without that field simply yields no verdict here,
     * and the call falls through to the ordinary reading of the result.
     *
     * @param result the {@code McpCallToolsResult} Workmate returned
     * @param toolName the name that was asked for
     * @throws GatewayException when the name was rejected
     */
    static void rejectUnknownTool(Object result, String toolName) throws GatewayException
    {
        if (result == null)
        {
            return;
        }
        Object unknown;
        try
        {
            unknown = readField(result.getClass().getField("unknownCalls"), result); //$NON-NLS-1$
        }
        catch (NoSuchFieldException | SecurityException e) // NOSONAR absence is not a verdict
        {
            return;
        }
        if (unknown instanceof Collection && !((Collection<?>)unknown).isEmpty())
        {
            throw GatewayException.unknownTool("1C:Workmate knows no tool named '" + toolName //$NON-NLS-1$
                + "' and rejected the call without running anything. Check the name - it is " //$NON-NLS-1$
                + "case-insensitive but must match a Workmate tool, for example JShellSession, " //$NON-NLS-1$
                + "JShell or JShellManual - and call ask_workmate again."); //$NON-NLS-1$
        }
    }

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
