/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.ProgressListener;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;

/** Starts questions sent through the co-located Workmate facade as background jobs. */
public class AskWorkmateTool implements IMcpTool
{
    public static final String NAME = "ask_workmate"; //$NON-NLS-1$

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_WAIT_SECONDS = 5;
    public static final int MAX_WAIT_SECONDS = 45;

    /**
     * Upper bound for a job's total budget. A job holds one of the shared workers and a
     * registry slot for its whole life, so an unbounded value would let a handful of calls
     * park the pool until EDT restarts. An hour is far past any real Workmate conversation.
     */
    static final int MAX_TIMEOUT_SECONDS = 3600;

    /**
     * How many jobs of this tool may run at once. Below the shared pool's worker count on
     * purpose: Workmate is invited to delegate a sub-question to this tool, and a parent job
     * occupies a worker while its child needs one, so leaving a worker free is what keeps a
     * one-level delegation from waiting on itself.
     */
    static final int MAX_CONCURRENT_JOBS = 3;

    private static final String KEY_QUESTION = "question"; //$NON-NLS-1$
    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String KEY_MAX_TOOL_ROUNDS = "maxToolRounds"; //$NON-NLS-1$
    private static final String KEY_SKILL_NAME = "skillName"; //$NON-NLS-1$
    private static final String KEY_TIMEOUT_SECONDS = "timeoutSeconds"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$

    /** Returns Workmate's answer as text, through its one-shot conversation facade. */
    private static final String MODE_ANSWER = "answer"; //$NON-NLS-1$

    /** Hands the question to Workmate's agentic chat; the answer goes to the chat panel. */
    private static final String MODE_CHAT = "chat"; //$NON-NLS-1$

    /**
     * Invokes one of Workmate's OWN tools directly, with no language model involved.
     * This mode is selected by the presence of {@link #KEY_WORKMATE_TOOL}, not by a
     * {@link #KEY_MODE} value, so that a caller cannot name a tool and a conflicting
     * mode in the same call.
     */
    private static final String KEY_WORKMATE_TOOL = "workmateTool"; //$NON-NLS-1$
    private static final String KEY_WORKMATE_ARGS = "workmateArgs"; //$NON-NLS-1$

    private static final String KEY_SHARE_MCP_TOOLS = "shareMcpTools"; //$NON-NLS-1$

    private final WorkmateGateway gateway;
    private final BackgroundJobs jobs;

    public AskWorkmateTool()
    {
        this(new WorkmateGateway(), BackgroundJobs.shared());
    }

    AskWorkmateTool(WorkmateGateway gateway)
    {
        this(gateway, BackgroundJobs.shared());
    }

    AskWorkmateTool(WorkmateGateway gateway, BackgroundJobs jobs)
    {
        this.gateway = gateway;
        this.jobs = jobs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start a background question to the 1C:Workmate plugin and return its jobId. " //$NON-NLS-1$
            + "Hands the question to an EXTERNAL agent: by default (shareMcpTools) Workmate may call " //$NON-NLS-1$
            + "EDT-MCP's own tools through this plugin, so it can READ the project and CHANGE code and " //$NON-NLS-1$
            + "metadata; workmateTool mode runs a named Workmate tool directly (JShell included). " //$NON-NLS-1$
            + "Poll the job with get_job_status instead of calling ask_workmate again. Requires " //$NON-NLS-1$
            + "a compatible Workmate installation in the same EDT JVM. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('ask_workmate')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Workmate reaches an external service and its own tool loop may have side effects,
        // so this is neither read-only nor idempotent. destructiveHint is TRUE and not a
        // conservative guess: the loop can edit metadata and BSL, and the direct workmateTool
        // mode can run arbitrary JShell code. Clients use this hint to decide whether a call
        // needs confirmation, and promising non-destructive here would be a false guarantee.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
            Boolean.TRUE);
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_QUESTION,
                "Non-empty question or instruction to send to 1C:Workmate. Required unless " //$NON-NLS-1$
                    + "workmateTool selects direct tool mode.") //$NON-NLS-1$
            .stringProperty(KEY_PROJECT_NAME,
                "Optional open EDT project name used as Workmate's context. " //$NON-NLS-1$
                    + "Omit to use Workmate's default project context.") //$NON-NLS-1$
            .integerProperty(KEY_MAX_TOOL_ROUNDS,
                "Optional positive limit for Workmate's internal tool-call " //$NON-NLS-1$
                    + "rounds; it applies per assistant turn, so a conversation continued to " //$NON-NLS-1$
                    + "reach a final answer spends it again on each turn.") //$NON-NLS-1$
            .stringProperty(KEY_SKILL_NAME,
                "Optional Workmate skill name. Omit to use '" //$NON-NLS-1$
                    + WorkmateGateway.DEFAULT_SKILL + "', the skill under which Workmate runs " //$NON-NLS-1$
                    + "its own tool loop; Workmate's plain 'raw' skill answers from the model " //$NON-NLS-1$
                    + "alone and inspects nothing.") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT_SECONDS,
                "Total wall-clock budget for the background job across all get_job_status " //$NON-NLS-1$
                    + "polls, in seconds; defaults to " + DEFAULT_TIMEOUT_SECONDS //$NON-NLS-1$
                    + " and accepts 1 to " + MAX_TIMEOUT_SECONDS //$NON-NLS-1$
                    + ". After this budget the job is failed - unless the request has already " //$NON-NLS-1$
                    + "reached Workmate, which cannot be taken back: the job then reports " //$NON-NLS-1$
                    + "Workmate's own outcome rather than a retryable timeout, because a retry " //$NON-NLS-1$
                    + "would run the same work twice. This is not the per-call " //$NON-NLS-1$
                    + "waitSeconds budget.") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Maximum time this start call may wait for completion before returning its " //$NON-NLS-1$
                    + "job snapshot, in " //$NON-NLS-1$
                    + "seconds; defaults to " + DEFAULT_WAIT_SECONDS + ", accepts 0 to " //$NON-NLS-1$ //$NON-NLS-2$
                    + MAX_WAIT_SECONDS + ". Use 0 to return immediately. This does not extend " //$NON-NLS-1$
                    + "the job's total timeoutSeconds budget.") //$NON-NLS-1$
            .stringProperty(KEY_WORKMATE_TOOL,
                "Exact name of a Workmate tool to invoke directly, e.g. 'JShellSession', " //$NON-NLS-1$
                    + "'JShellManual' or 'JShell'. Passing this parameter selects the direct " //$NON-NLS-1$
                    + "tool mode by itself: question and mode are not used, and no language " //$NON-NLS-1$
                    + "model is involved, so the tool either runs or returns its own error.") //$NON-NLS-1$
            .stringProperty(KEY_WORKMATE_ARGS,
                "Direct tool mode only: JSON OBJECT with that tool's arguments, e.g. {} or " //$NON-NLS-1$
                    + "{\"scope\":\"eclipse\",\"code\":\"...\"}. Defaults to an empty object.") //$NON-NLS-1$
            .booleanProperty(KEY_SHARE_MCP_TOOLS,
                "When true, the question is prefixed with instructions that " //$NON-NLS-1$
                    + "let Workmate call EDT-MCP's own tools through this plugin's in-process " //$NON-NLS-1$
                    + "bridge, so it can inspect the real project instead of answering from " //$NON-NLS-1$
                    + "general 1C knowledge. Defaults to true for mode '" + MODE_ANSWER //$NON-NLS-1$
                    + "' and to false for mode '" + MODE_CHAT //$NON-NLS-1$
                    + "', where the project's own .workmate rules already carry the same " //$NON-NLS-1$
                    + "instructions; pass true there for a project that has no such rules.") //$NON-NLS-1$
            .stringProperty(KEY_MODE,
                "'" + MODE_ANSWER + "' (default) runs Workmate's tool loop " //$NON-NLS-1$ //$NON-NLS-2$
                    + "and RETURNS its answer as text: it inspects the project with its own " //$NON-NLS-1$
                    + "tools and, through this plugin's bridge, with EDT-MCP's, so it can " //$NON-NLS-1$
                    + "also change code and metadata. '" + MODE_CHAT //$NON-NLS-1$
                    + "' hands the same question to Workmate's agentic chat instead; the work " //$NON-NLS-1$
                    + "happens there and its answer is rendered in the EDT chat panel for a " //$NON-NLS-1$
                    + "human, so it is NOT returned here. Prefer '" + MODE_ANSWER //$NON-NLS-1$
                    + "' unless a human should continue the conversation in the panel.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params != null ? params.get(KEY_WAIT_SECONDS) : null,
                DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        }

        if (params != null && params.containsKey(KEY_WORKMATE_TOOL))
        {
            return startWorkmateTool(params, waitSeconds.intValue());
        }

        if (params == null || !params.containsKey(KEY_QUESTION))
        {
            return ToolResult.error(
                "ask_workmate requires a non-empty question, unless workmateTool selects direct " //$NON-NLS-1$
                    + "tool mode. Provide one of those inputs, then poll the returned jobId with " //$NON-NLS-1$
                    + "get_job_status.") //$NON-NLS-1$
                .toJson();
        }

        return start(params, waitSeconds.intValue());
    }

    /**
     * Runs one of Workmate's own tools with no model in the loop, as a background job so the
     * response shape stays identical to the other modes.
     *
     * @param params validated tool arguments
     * @param waitSeconds bound for this single call
     * @return the rendered job report
     */
    private String startWorkmateTool(Map<String, String> params, int waitSeconds)
    {
        String workmateTool = trimToNull(JsonUtils.extractStringArgument(params, KEY_WORKMATE_TOOL));
        if (workmateTool == null)
        {
            return ToolResult.error(
                "workmateTool must name a Workmate tool, for example 'JShellSession'. Provide " //$NON-NLS-1$
                    + "a non-empty name and retry ask_workmate.").toJson(); //$NON-NLS-1$
        }
        String workmateArgs = trimToNull(JsonUtils.extractStringArgument(params, KEY_WORKMATE_ARGS));

        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (params.containsKey(KEY_TIMEOUT_SECONDS))
        {
            Integer parsedTimeout = optionalPositiveInt(params, KEY_TIMEOUT_SECONDS);
            if (parsedTimeout == null || parsedTimeout.intValue() > MAX_TIMEOUT_SECONDS)
            {
                return timeoutSecondsError(params.get(KEY_TIMEOUT_SECONDS));
            }
            timeoutSeconds = parsedTimeout.intValue();
        }

        final int jobTimeoutSeconds = timeoutSeconds;
        try
        {
            JobSnapshot started = jobs.start(NAME, TimeUnit.SECONDS.toMillis(jobTimeoutSeconds),
                MAX_CONCURRENT_JOBS,
                "Accepted the direct Workmate tool call.", progress -> { //$NON-NLS-1$
                    try
                    {
                        // Commit-capable, not progress::add: a Workmate tool can run arbitrary
                        // code, and once it is invoked no timeout can take that back.
                        String out = gateway.callWorkmateTool(workmateTool, workmateArgs,
                            jobTimeoutSeconds, jobProgress(progress));
                        return new WorkmateResponse(out == null || out.isEmpty()
                            ? "(the tool returned no text)" : out, null); //$NON-NLS-1$
                    }
                    catch (GatewayException e)
                    {
                        throw new WorkmateJobException(actionableMessage(e, jobTimeoutSeconds), e);
                    }
                });
            if (started == null)
            {
                return tooManyJobsError();
            }
            return BackgroundJobRenderer.render(BackgroundJobPolling.await(jobs,
                started.getId(), waitSeconds));
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error(
                "Could not start ask_workmate because the background-job registry is full or " //$NON-NLS-1$
                    + "stopping: " + e.getMessage() + ". Poll existing jobs with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "get_job_status and retry, or " //$NON-NLS-1$
                    + "restart EDT if the bundle is stopping.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Adapts a job's reporter to the gateway's listener, carrying BOTH directions: progress out,
     * and the commit handshake back - which is what keeps a job whose request is already on its
     * way from being published as a retryable failure.
     *
     * @param progress the running job's reporter
     * @return a listener bound to that job
     */
    private static ProgressListener jobProgress(ProgressReporter progress)
    {
        return new ProgressListener()
        {
            @Override
            public void onProgress(String message)
            {
                progress.add(message);
            }

            @Override
            public boolean onTryCommit()
            {
                return progress.tryCommit();
            }
        };
    }

    /**
     * Text prepended to the question so Workmate can reach EDT-MCP's own tools.
     * <p>
     * Everything it names is JDK or standard OSGi API and the snippet is complete, which
     * matters: Workmate's JShell tool forbids improvised Java API, and a caller that has
     * to prove the bridge in prose on every call would not get an answer at all.
     *
     * @param projectName optional project the caller asked about, used in the example
     * @return the preamble, ending with a blank line before the question
     */
    static String mcpBridgePreamble(String projectName)
    {
        // The example has to RUN as written - the whole point of the preamble is that Workmate
        // may execute it without improvising Java API. With no project named there is nothing
        // truthful to put in projectName, and a placeholder would make the snippet fail with
        // "project not found" instead of demonstrating the bridge, so the example becomes the
        // discovery call that needs no arguments at all.
        //
        // The name lands inside a JSON string inside a Java string literal, so a quote or
        // a backslash in it would otherwise produce a snippet that does not compile.
        String example = projectName == null
            ? "mcp.apply(\"list_projects\", \"{}\")" //$NON-NLS-1$
            : "mcp.apply(\"get_metadata_objects\", " //$NON-NLS-1$
                + "\"{\\\"projectName\\\":\\\"" //$NON-NLS-1$
                + projectName.replace("\\", "\\\\\\\\").replace("\"", "\\\\\\\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + "\\\",\\\"metadataType\\\":\\\"Catalog\\\"}\")"; //$NON-NLS-1$
        return "The EDT-MCP plugin runs in this same EDT and publishes its entry point as an " //$NON-NLS-1$
            + "ordinary OSGi service under the JDK type " //$NON-NLS-1$
            + "java.util.function.BiFunction<String,String,String> with the service property " //$NON-NLS-1$
            + "edt.mcp.bridge=v1. apply(toolName, argumentsJson) returns the MCP tools/call " //$NON-NLS-1$
            + "response; the Supplier<String> alias with the same property lists every tool. " //$NON-NLS-1$
            + "Its tools read and change this configuration - metadata, BSL modules, forms, " //$NON-NLS-1$
            + "markers, Git, tests - so prefer them over guessing whenever the question is " //$NON-NLS-1$
            + "about what is actually in the project.\n\n" //$NON-NLS-1$
            + "Every type below is JDK or standard OSGi API, so no unproven Java API is " //$NON-NLS-1$
            + "involved. Call it with JShellManual (manual_id jshell_edt_canonical_imports), " //$NON-NLS-1$
            + "then JShellSession, then JShell with scope=eclipse and this code:\n\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "var ctx = org.osgi.framework.FrameworkUtil\n" //$NON-NLS-1$
            + "    .getBundle(org.eclipse.core.runtime.Platform.class).getBundleContext();\n" //$NON-NLS-1$
            + "var refs = ctx.getServiceReferences(java.util.function.BiFunction.class, " //$NON-NLS-1$
            + "\"(edt.mcp.bridge=v1)\");\n" //$NON-NLS-1$
            + "var mcp = ctx.getService(refs.iterator().next());\n" //$NON-NLS-1$
            + "System.out.println(" + example + ");\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Reuse one session for follow-up calls. Report what the tool actually " //$NON-NLS-1$
            + "returned and never invent a result.\n\n" //$NON-NLS-1$
            + "ask_workmate is in that list on purpose: you may delegate a self-contained " //$NON-NLS-1$
            + "sub-question to it as a sub-agent. It answers asynchronously - take the " //$NON-NLS-1$
            + "jobId from its reply and poll it with get_job_status instead of waiting - " //$NON-NLS-1$
            + "and only a few such jobs may run at once, so delegate one level deep, not a " //$NON-NLS-1$
            + "chain.\n\n" //$NON-NLS-1$
            + toolCatalogue() + "Question:\n"; //$NON-NLS-1$
    }

    /**
     * Lists the callable tool names so Workmate does not have to spend a JShell round
     * discovering them. Names only: the full specifications are ~40 KB, far too much to
     * prepend to every question, while the names alone are ~1.5 KB and are enough for the
     * model to pick one and ask the bridge for its guide.
     *
     * @return a paragraph ending with a blank line, or an empty string when no tool is
     *         registered (a headless runtime, or before registration)
     */
    private static String toolCatalogue()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        String names = registry.getAllTools().stream()
            .map(IMcpTool::getName)
            // Only what a bridge call would actually be allowed to run: naming a tool the
            // user disabled would send Workmate off to call it and get refused.
            .filter(registry::isToolEnabled)
            .sorted()
            .collect(Collectors.joining(", ")); //$NON-NLS-1$
        if (names.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return "Tools reachable through the bridge right now, by name only: " + names //$NON-NLS-1$
            + ".\n\nThe full description of any one of them - what it does, every parameter " //$NON-NLS-1$
            + "and examples - is returned by a tool of its own, get_tool_guide. Call it " //$NON-NLS-1$
            + "through the same bridge before using a tool you do not know, instead of " //$NON-NLS-1$
            + "guessing its arguments:\n" //$NON-NLS-1$
            + "mcp.apply(\"get_tool_guide\", \"{\\\"toolName\\\":\\\"find_references\\\"}\")\n\n"; //$NON-NLS-1$
    }

    private String start(Map<String, String> params, int waitSeconds)
    {
        String question = trimToNull(JsonUtils.extractStringArgument(params, KEY_QUESTION));
        if (question == null)
        {
            return ToolResult.error(
                "question must contain non-whitespace text. Provide a question or instruction " //$NON-NLS-1$
                    + "for 1C:Workmate and retry ask_workmate.").toJson(); //$NON-NLS-1$
        }

        Integer maxToolRounds = optionalPositiveInt(params, KEY_MAX_TOOL_ROUNDS);
        if (params.containsKey(KEY_MAX_TOOL_ROUNDS) && maxToolRounds == null)
        {
            return positiveIntegerError(KEY_MAX_TOOL_ROUNDS, params.get(KEY_MAX_TOOL_ROUNDS));
        }

        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (params.containsKey(KEY_TIMEOUT_SECONDS))
        {
            Integer parsedTimeout = optionalPositiveInt(params, KEY_TIMEOUT_SECONDS);
            if (parsedTimeout == null || parsedTimeout.intValue() > MAX_TIMEOUT_SECONDS)
            {
                return timeoutSecondsError(params.get(KEY_TIMEOUT_SECONDS));
            }
            timeoutSeconds = parsedTimeout.intValue();
        }

        String projectName = trimToNull(JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME));
        IProject project = null;
        if (projectName != null)
        {
            ProjectContext context = ProjectContext.of(projectName);
            if (!context.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            if (!context.isOpen())
            {
                return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                    + "' is closed. Open it in EDT or omit projectName, then retry ask_workmate.") //$NON-NLS-1$
                    .toJson();
            }
            project = context.project();
        }

        String skillName = trimToNull(JsonUtils.extractStringArgument(params, KEY_SKILL_NAME));
        String mode = trimToNull(JsonUtils.extractStringArgument(params, KEY_MODE));
        if (mode == null)
        {
            mode = MODE_ANSWER;
        }
        if (!MODE_ANSWER.equals(mode) && !MODE_CHAT.equals(mode))
        {
            return ToolResult.error("Unsupported mode '" + mode + "'. Use '" + MODE_ANSWER //$NON-NLS-1$ //$NON-NLS-2$
                + "' to get Workmate's answer back as text, or '" + MODE_CHAT //$NON-NLS-1$
                + "' to hand the question to Workmate's agentic chat. To run one of " //$NON-NLS-1$
                + "Workmate's own tools directly, drop mode and pass workmateTool instead. " //$NON-NLS-1$
                + "Then retry ask_workmate.").toJson(); //$NON-NLS-1$
        }

        final boolean chatMode = MODE_CHAT.equals(mode);

        // The facade path knows nothing about the bridge, so it gets the preamble by default -
        // without it the model has no way to learn that EDT-MCP is reachable at all. The
        // agentic chat loads the project's own .workmate rules instead, which already carry
        // the same instructions, so there the DEFAULT is off; an explicit true is still
        // honoured, and that is what makes the bridge reachable from a chat opened on a
        // project that has no .workmate rules of its own.
        final boolean shareMcpTools =
            JsonUtils.extractBooleanArgument(params, KEY_SHARE_MCP_TOOLS, !chatMode);
        final String jobQuestion =
            shareMcpTools ? mcpBridgePreamble(projectName) + question : question;

        final IProject jobProject = project;
        final int jobTimeoutSeconds = timeoutSeconds;
        try
        {
            JobSnapshot started = jobs.start(NAME, TimeUnit.SECONDS.toMillis(jobTimeoutSeconds),
                MAX_CONCURRENT_JOBS,
                "Accepted the question.", progress -> { //$NON-NLS-1$
                    // Not progress::add: every path below reaches a point after which its
                    // request can no longer be taken back - the chat hand-off, and equally the
                    // facade and direct-tool dispatches, because Workmate's tools change this
                    // configuration. Past that point the job must never be published as a
                    // retryable failure, or the retry performs the same work twice.
                    ProgressListener listener = jobProgress(progress);
                    try
                    {
                        if (chatMode)
                        {
                            gateway.pushToChat(jobProject, jobQuestion, listener);
                            return new WorkmateResponse(chatHandoffAnswer(), null);
                        }
                        WorkmateResponse response = gateway.ask(jobProject, jobQuestion,
                            maxToolRounds, skillName, jobTimeoutSeconds, listener);
                        if (response == null || trimToNull(response.getText()) == null)
                        {
                            throw new WorkmateJobException(emptyAnswerMessage(
                                response == null ? 0 : response.getContinuations()));
                        }
                        return response;
                    }
                    catch (GatewayException e)
                    {
                        throw new WorkmateJobException(
                            actionableMessage(e, jobTimeoutSeconds), e);
                    }
                });
            if (started == null)
            {
                return tooManyJobsError();
            }
            return BackgroundJobRenderer.render(BackgroundJobPolling.await(jobs,
                started.getId(), waitSeconds));
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error(
                "Could not start ask_workmate because the background-job registry is full or " //$NON-NLS-1$
                    + "stopping: " + e.getMessage() + ". Poll existing jobs with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "get_job_status and retry, or " //$NON-NLS-1$
                    + "restart EDT if the bundle is stopping.").toJson(); //$NON-NLS-1$
        }
    }

    private static Integer optionalPositiveInt(Map<String, String> params, String key)
    {
        if (params == null || !params.containsKey(key))
        {
            return null;
        }
        int value = JsonUtils.extractIntArgument(params, key, Integer.MIN_VALUE);
        return value > 0 ? value : null;
    }

    private static String positiveIntegerError(String key, String value)
    {
        return ToolResult.error(key + " must be a positive integer, but was '" //$NON-NLS-1$
            + value + "'. Pass " + key + ">=1 or omit it to use the default.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reports that the admission limit turned this start away.
     * <p>
     * The decision itself belongs to {@code BackgroundJobs.start(.., maxRunning, ..)}, which
     * counts and admits under one lock: counting here and starting afterwards would let
     * concurrent requests all see room and all start, defeating the reserved worker. This
     * method only phrases the refusal.
     * <p>
     * Workmate is told it may delegate a sub-question to this very tool, which is useful and
     * deliberate - but a job holds one of the shared workers for its whole life, so a chain
     * of nested starts would park the pool and every job in it would then wait out its
     * budget. Saying so beats letting the caller discover it as a hang.
     *
     * @return the actionable error for a refused start
     */
    private static String tooManyJobsError()
    {
        return ToolResult.error(NAME + " already has " + MAX_CONCURRENT_JOBS //$NON-NLS-1$
            + " jobs running, which is the limit. Poll the jobId of a running job with " //$NON-NLS-1$
            + "get_job_status and act " //$NON-NLS-1$
            + "on its answer before starting another; if this happened while delegating a " //$NON-NLS-1$
            + "sub-question, ask it directly instead of nesting deeper.").toJson(); //$NON-NLS-1$
    }

    private static String timeoutSecondsError(String value)
    {
        return ToolResult.error("timeoutSeconds must be an integer from 1 to " //$NON-NLS-1$
            + MAX_TIMEOUT_SECONDS + ", but was '" + value + "'. A job holds a worker for its " //$NON-NLS-1$ //$NON-NLS-2$
            + "whole budget, so pass a realistic bound or omit it to use the default of " //$NON-NLS-1$
            + DEFAULT_TIMEOUT_SECONDS + " seconds.").toJson(); //$NON-NLS-1$
    }

    /**
     * The whole answer for a chat hand-off. Workmate's {@code IChat.askQuestion} returns
     * {@code void}, so there is nothing to report but where the answer will appear - saying
     * anything more would be inventing a result this tool never received.
     *
     * @return the hand-off notice shown in place of an answer
     */
    private static String chatHandoffAnswer()
    {
        return "The question was handed to the 1C:Workmate chat in EDT. Workmate works it there " //$NON-NLS-1$
            + "with its own tools and may search or edit the configuration; its answer appears " //$NON-NLS-1$
            + "in the EDT chat panel and is not returned through MCP. Use mode='" + MODE_ANSWER //$NON-NLS-1$
            + "' when you need the answer text back here."; //$NON-NLS-1$
    }

    private static String actionableMessage(GatewayException error, int timeoutSeconds)
    {
        switch (error.getKind())
        {
            case NOT_INSTALLED:
                return "1C:Workmate is not installed: " + error.getDetail() //$NON-NLS-1$
                    + ". Install it in this EDT instance (Help > Install New Software, " //$NON-NLS-1$
                    + "repository https://code.1c.ai/plugin/), restart EDT, then retry " //$NON-NLS-1$
                    + "ask_workmate."; //$NON-NLS-1$
            case DISABLED:
                return "1C:Workmate is installed but switched off: " + error.getDetail() //$NON-NLS-1$
                    + ". Enable it in EDT preferences (Window > Preferences > 1C:Workmate), " //$NON-NLS-1$
                    + "then retry ask_workmate."; //$NON-NLS-1$
            case NO_CLIENT_TOKEN:
                return "1C:Workmate has no valid access key: " + error.getDetail() //$NON-NLS-1$
                    + ". Generate a key on the 1C ITS portal and paste it into Window > " //$NON-NLS-1$
                    + "Preferences > 1C:Workmate > User Token, then retry ask_workmate."; //$NON-NLS-1$
            case INCOMPATIBLE:
                return "Incompatible 1C:Workmate version or structure: " + error.getDetail() //$NON-NLS-1$
                    + ". Install a 1C:Workmate build compatible with 1.0.5 or update EDT-MCP's " //$NON-NLS-1$
                    + "Workmate adapter, then retry ask_workmate."; //$NON-NLS-1$
            case NOT_READY:
                return "1C:Workmate is installed but not initialized: " + error.getDetail() //$NON-NLS-1$
                    + ". Open Workmate in EDT (or restart EDT), wait for it to initialize, " //$NON-NLS-1$
                    + "then retry ask_workmate."; //$NON-NLS-1$
            case TIMED_OUT:
                return "1C:Workmate did not answer within " + timeoutSeconds //$NON-NLS-1$
                    + " seconds. Retry with a larger timeoutSeconds value or check Workmate " //$NON-NLS-1$
                    + "and network status in EDT."; //$NON-NLS-1$
            case TIMED_OUT_AFTER_DISPATCH:
                // Deliberately NOT the "just retry with a bigger budget" advice: the request
                // reached Workmate, whose tools change this configuration, and cancelling the
                // wait does not undo that. A blind retry would run the same work twice.
                return "1C:Workmate did not answer within " + timeoutSeconds //$NON-NLS-1$
                    + " seconds, and the request had already been sent - Workmate may still be " //$NON-NLS-1$
                    + "working on it, and its tools can change this configuration. Do NOT " //$NON-NLS-1$
                    + "simply retry: check the Workmate chat panel and the project (" //$NON-NLS-1$
                    + "get_project_errors, git status) for what it already did, and only then " //$NON-NLS-1$
                    + "start a new ask_workmate job, with a larger timeoutSeconds."; //$NON-NLS-1$
            case CALL_FAILED:
            default:
                return "1C:Workmate failed to answer: " + error.getDetail() //$NON-NLS-1$
                    + ". Check Workmate sign-in, network, and settings in EDT, then retry " //$NON-NLS-1$
                    + "ask_workmate."; //$NON-NLS-1$
        }
    }

    /**
     * Reports that Workmate said nothing at all.
     *
     * <p>The sentence turns on whether the conversation was actually continued (#427): saying
     * "even after being asked to continue" when no continuation was sent - which happens when the
     * installed Workmate exposes no conversation handle to continue WITH - would send the reader
     * after a sign-in problem that is not there.
     *
     * @param continuations how many continuations the adapter actually sent
     * @return the actionable message
     */
    private static String emptyAnswerMessage(int continuations)
    {
        return "1C:Workmate returned an empty answer" //$NON-NLS-1$
            + (continuations > 0
                ? ", even after being asked " + continuations + " time(s) to continue the same " //$NON-NLS-1$ //$NON-NLS-2$
                    + "conversation" //$NON-NLS-1$
                : "") //$NON-NLS-1$
            + ". Open Workmate in EDT, verify that it is signed in and configured, then start a " //$NON-NLS-1$
            + "new ask_workmate job."; //$NON-NLS-1$
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class WorkmateJobException extends Exception
    {
        private static final long serialVersionUID = 1L;

        WorkmateJobException(String message)
        {
            super(message);
        }

        WorkmateJobException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
