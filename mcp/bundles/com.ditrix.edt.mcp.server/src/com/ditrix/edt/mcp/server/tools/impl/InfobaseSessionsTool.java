/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionErrorProjection;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.TerminationResult;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonNull;

/** Lists and terminates sessions of a running standalone-server infobase. */
public class InfobaseSessionsTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "infobase_sessions"; //$NON-NLS-1$

    private static final String ACTION_LIST = "list"; //$NON-NLS-1$
    private static final String ACTION_TERMINATE = "terminate"; //$NON-NLS-1$
    private static final String KEY_REACHABLE = "reachable"; //$NON-NLS-1$
    private static final String KEY_UNREACHABLE_REASON = "unreachableReason"; //$NON-NLS-1$
    private static final String KEY_SESSIONS = "sessions"; //$NON-NLS-1$
    private static final String KEY_VERIFICATION = "verification"; //$NON-NLS-1$
    private static final String KEY_VERIFICATION_REASON = "verificationReason"; //$NON-NLS-1$
    private static final String VERIFICATION_VERIFIED = "verified"; //$NON-NLS-1$
    private static final String VERIFICATION_MISMATCHED = "mismatched"; //$NON-NLS-1$
    private static final String VERIFICATION_NOT_VERIFIABLE = "not_verifiable"; //$NON-NLS-1$
    /**
     * Aggregate budget for a bulk terminate. Each command is bounded on its own, but all=true
     * lets the caller choose HOW MANY run, so without this the call lasts as long as the
     * session count demands and the per-command bound buys nothing.
     */
    private static final long BULK_TERMINATION_BUDGET_MS = 60_000L;
    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List or terminate sessions on a running standalone-server infobase. " //$NON-NLS-1$
            + "DESTRUCTIVE for terminate: pass confirm=true; bulk termination skips Designer, " //$NON-NLS-1$
            + "while its exact full UUID can target it. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('infobase_sessions')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose standalone-server application owns the sessions (required).", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications; defaults to the project's default application.") //$NON-NLS-1$
            .enumProperty(McpKeys.ACTION,
                "list (default) reads sessions; terminate ends the selected session(s).", //$NON-NLS-1$
                ACTION_LIST, ACTION_TERMINATE)
            .stringProperty("sessionId", //$NON-NLS-1$
                "For terminate, the full session UUID or numeric session-id returned by list; " //$NON-NLS-1$
                    + "a Designer session requires its exact full UUID.") //$NON-NLS-1$
            .booleanProperty("all", //$NON-NLS-1$
                "For terminate, true selects every non-agent session; Designer is always excluded.") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Required true for terminate; list never changes sessions.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Optional text shown to a terminated user through ibcmd --error-message.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the tool call succeeded.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("error", "Human-readable failure message when success=false.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION,
                "The requested action that this result refers to: list or terminate.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Resolved standalone-server application ID.") //$NON-NLS-1$
            .booleanProperty(KEY_REACHABLE,
                "true only when ibcmd produced a real result; false never means an empty list.") //$NON-NLS-1$
            .stringProperty(KEY_UNREACHABLE_REASON,
                "Present with reachable=false and names why sessions could not be inspected.") //$NON-NLS-1$
            .objectArrayProperty(KEY_SESSIONS,
                "For list, observed sessions; for terminate, only sessions observed gone. " //$NON-NLS-1$
                    + "Successful records include applicationKindIsDesigner, which reports " //$NON-NLS-1$
                    + "whether the raw app-id is Designer without claiming EDT ownership.") //$NON-NLS-1$
            .integerProperty("count", "Number of observed sessions on a readable list.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("terminatedCount", //$NON-NLS-1$
                "Number of sessions observed gone after the terminate command.") //$NON-NLS-1$
            .integerProperty("attemptedCount", //$NON-NLS-1$
                "Number of terminate commands accepted when the session list could not be re-read.") //$NON-NLS-1$
            .integerProperty("notAttemptedCount", //$NON-NLS-1$
                "Selected sessions the bulk terminate never reached before its budget ran out.") //$NON-NLS-1$
            .enumProperty(KEY_VERIFICATION,
                "Terminate read-back: verified, mismatched, or not_verifiable.", //$NON-NLS-1$
                VERIFICATION_VERIFIED, VERIFICATION_MISMATCHED, VERIFICATION_NOT_VERIFIABLE)
            .stringProperty(KEY_VERIFICATION_REASON,
                "Why the terminate read-back mismatched or could not be performed.") //$NON-NLS-1$
            .booleanProperty("mutationCommitted", //$NON-NLS-1$
                "Present as true when read-back confirms at least one targeted session is gone.") //$NON-NLS-1$
            .booleanProperty("mutationOutcomeUnknown", //$NON-NLS-1$
                "Present as true when a failed terminate may have changed session state.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status or protection note.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean returnsInfobaseData()
    {
        // Successful list and verified-termination payloads carry live user and host values.
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (required != null)
        {
            return required;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String action = JsonUtils.extractStringArgument(params, McpKeys.ACTION);
        action = action == null || action.isBlank() ? ACTION_LIST : action.toLowerCase(Locale.ROOT);
        String sessionId = JsonUtils.extractStringArgument(params, "sessionId"); //$NON-NLS-1$
        sessionId = sessionId == null ? null : sessionId.trim();
        boolean all = JsonUtils.extractBooleanArgument(params, "all", false); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        String message = JsonUtils.extractStringArgument(params, McpKeys.MESSAGE);

        String validation = validate(action, sessionId, all, confirm, message);
        if (validation != null)
        {
            return ToolResult.error(validation).toJson();
        }

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        try
        {
            ResolvedApplication resolved = resolveApplication(projectName, applicationId);
            if (resolved.errorJson != null)
            {
                return resolved.errorJson;
            }
            synchronized (LaunchLifecycleUtils.lockFor(projectName, resolved.application.getId()))
            {
                return ACTION_LIST.equals(action)
                    ? list(projectName, resolved.application) : terminate(projectName,
                        resolved.application, sessionId, all, message);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Infobase sessions failed for project " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Infobase sessions failed: " //$NON-NLS-1$
                + PlatformFailures.describe(e)).toJson();
        }
    }

    /** Validates action-specific safety and selector rules before platform access. */
    static String validate(String action, String sessionId, boolean all, boolean confirm,
        String message)
    {
        if (!ACTION_LIST.equals(action) && !ACTION_TERMINATE.equals(action))
        {
            return "Invalid action: '" + action + "'. Allowed values: list, terminate."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (ACTION_LIST.equals(action))
        {
            if ((sessionId != null && !sessionId.isBlank()) || all || confirm)
            {
                return "sessionId, all, and confirm apply only to action='terminate'."; //$NON-NLS-1$
            }
            return null;
        }
        if (!confirm)
        {
            return "action='terminate' requires confirm=true."; //$NON-NLS-1$
        }
        boolean hasSession = sessionId != null && !sessionId.isBlank();
        if (hasSession == all)
        {
            return "For action='terminate', specify exactly one of sessionId or all=true."; //$NON-NLS-1$
        }
        if (hasSession && !InfobaseSessionErrorProjection.isSessionSelector(sessionId))
        {
            return "sessionId must be a full UUID or the numeric session-id returned by list."; //$NON-NLS-1$
        }
        // ProcessBuilder.start() refuses a NUL-bearing argument BEFORE it creates anything, so a
        // message carrying one could only ever fail - and would do it late, as an unknown
        // mutation outcome. Refusing it here keeps the answer definitive and actionable.
        if (message != null && message.indexOf(0) >= 0)
        {
            return "message must not contain a NUL character (U+0000): the platform refuses " //$NON-NLS-1$
                + "such an argument, so nothing would be terminated. Remove it and retry."; //$NON-NLS-1$
        }
        return null;
    }

    /** Resolves the explicit application or the project's default application. */
    private static ResolvedApplication resolveApplication(String projectName, String applicationId)
        throws Exception
    {
        ApplicationSupport.ManagerResult managerResult = ApplicationSupport.resolveManager(projectName);
        if (!managerResult.ok())
        {
            return ResolvedApplication.error(managerResult.errorJson());
        }
        IProject project = managerResult.project();
        IApplicationManager manager = managerResult.manager();
        Optional<IApplication> application = applicationId == null || applicationId.isBlank()
            ? manager.getDefaultApplication(project)
            : manager.getApplication(project, applicationId);
        if (application.isEmpty())
        {
            String target = applicationId == null || applicationId.isBlank()
                ? "Project '" + projectName + "' has no default application." //$NON-NLS-1$ //$NON-NLS-2$
                : "Application not found: " + applicationId + "."; //$NON-NLS-1$ //$NON-NLS-2$
            return ResolvedApplication.error(ToolResult.error(target
                + " Use get_applications to choose a standalone-server application.").toJson()); //$NON-NLS-1$
        }
        return ResolvedApplication.of(application.get());
    }

    /** Renders a readable list or an explicit unreachable observation. */
    private static String list(String projectName, IApplication application)
    {
        ReadResult result = InfobaseSessionSupport.listSessions(application);
        ToolResult response = baseResult(ACTION_LIST, projectName, application);
        if (!result.isReadable())
        {
            return response.put(KEY_REACHABLE, false)
                .put(KEY_UNREACHABLE_REASON, result.unreachableReason())
                .put(McpKeys.MESSAGE, "Sessions could not be inspected; this is not proof that " //$NON-NLS-1$
                    + "the infobase has no sessions.").toJson(); //$NON-NLS-1$
        }
        List<Map<String, Object>> sessions = sessionMaps(result.sessions());
        return response.put(KEY_REACHABLE, true)
            .put(KEY_SESSIONS, sessions)
            .put("count", sessions.size()) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, sessions.isEmpty()
                ? "The readable session list is empty." //$NON-NLS-1$
                : "Read " + sessions.size() + " infobase session(s).").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Lists first, applies Designer selection rules, obtains consent, and terminates the selection. */
    private static String terminate(String projectName, IApplication application,
        String requestedSessionId, boolean all, String message)
    {
        ReadResult current = InfobaseSessionSupport.listSessions(application);
        if (!current.isReadable())
        {
            return baseError("Sessions could not be inspected, so none were terminated.", //$NON-NLS-1$
                projectName, application).put(KEY_REACHABLE, false)
                    .put(KEY_UNREACHABLE_REASON, current.unreachableReason()).toJson();
        }

        Selection selection = selectSessions(current.sessions(), requestedSessionId, all);
        if (selection.error != null)
        {
            return baseError(selection.error, projectName, application)
                .put(KEY_REACHABLE, true).toJson();
        }
        if (selection.sessions.isEmpty())
        {
            return baseResult(ACTION_TERMINATE, projectName, application)
                .put(KEY_REACHABLE, true)
                .put(KEY_SESSIONS, List.of())
                .put("terminatedCount", 0) //$NON-NLS-1$
                .put(McpKeys.MESSAGE, emptySelectionMessage(current.sessions())).toJson();
        }

        List<String> targets = new ArrayList<>();
        for (SessionInfo session : selection.sessions)
        {
            targets.add(displayId(session));
        }
        boolean designerSelected = selection.sessions.stream()
            .anyMatch(InfobaseSessionsTool::isDesignerSession);
        ConsentPreview preview = new ConsentPreview("Terminate infobase sessions", //$NON-NLS-1$
            "This terminates " + (designerSelected
                ? "the exact Designer/configurator session" //$NON-NLS-1$
                : selection.sessions.size() + " live non-agent session(s)") + " on " //$NON-NLS-1$ //$NON-NLS-2$
                + "application '" + application.getName() + "'.", //$NON-NLS-1$ //$NON-NLS-2$
            selection.sessions.size(), targets);
        DestructiveConsentGate.ConsentDecision decision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (decision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return baseError(DestructiveConsentGate.consentDeniedMessage(decision, NAME),
                projectName, application).put(KEY_REACHABLE, true).toJson();
        }

        List<SessionInfo> attempted = new ArrayList<>();
        int notAttempted = 0;
        long startedAt = System.nanoTime();
        for (SessionInfo session : selection.sessions)
        {
            if (!bulkBudgetAllowsAnotherAttempt(attempted.size(), System.nanoTime() - startedAt))
            {
                notAttempted = selection.sessions.size() - attempted.size();
                break;
            }
            TerminationResult result = InfobaseSessionSupport.terminateSession(application,
                session.sessionId(), message);
            if (!result.terminated())
            {
                if (attempted.isEmpty())
                {
                    return firstTerminationFailureResult(projectName, application.getId(), result);
                }
                return terminationSequenceStoppedResult(projectName, application.getId(),
                    attempted.size(), result.unreachableReason());
            }
            attempted.add(session);
        }

        // ibcmd exits 0 for a session UUID that no longer exists, so its exit code cannot say a
        // session was terminated. A terminate completes before ibcmd returns, so re-reading the
        // list once reports what is actually gone.
        ReadResult after = InfobaseSessionSupport.listSessions(application);
        return terminationReadBackResult(projectName, application.getId(), attempted, after,
            notAttempted);
    }

    /**
     * Whether the bulk loop may start another terminate command.
     *
     * <p>The first attempt always runs. A budget that could refuse before anything was tried
     * would turn a legitimate request into a silent no-op, and the per-command bound already
     * caps how long that one attempt can take.
     *
     * @param attemptedCount commands already accepted in this call
     * @param elapsedNanos time spent in the loop so far
     * @return true while another command may start
     */
    static boolean bulkBudgetAllowsAnotherAttempt(int attemptedCount, long elapsedNanos)
    {
        return attemptedCount == 0
            || elapsedNanos < TimeUnit.MILLISECONDS.toNanos(BULK_TERMINATION_BUDGET_MS);
    }

    /** Selects the honest first-attempt error from whether a command may have started. */
    static String firstTerminationFailureResult(String projectName, String applicationId,
        TerminationResult result)
    {
        return result.mutationOutcomeUnknown()
            ? firstTerminationAttemptFailedResult(projectName, applicationId,
                result.unreachableReason())
            : firstTerminationAttemptNotStartedResult(projectName, applicationId,
                result.unreachableReason());
    }

    /** Builds an ordinary error when the first terminate command never reached launch. */
    static String firstTerminationAttemptNotStartedResult(String projectName, String applicationId,
        String reason)
    {
        return ToolResult.error("Session termination did not start: " + reason //$NON-NLS-1$
            + " No terminate command was launched, so this call did not remove a session.") //$NON-NLS-1$
                .put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, false)
                .put(KEY_UNREACHABLE_REASON, reason)
                .toJson();
    }

    /** Builds an unverified error after the first terminate command fails. */
    static String firstTerminationAttemptFailedResult(String projectName, String applicationId,
        String reason)
    {
        return ToolResult.errorWithUnknownMutationOutcome(
            "Session termination command failed: " + reason //$NON-NLS-1$
            + " The session list was not re-read, so it is unknown whether the targeted session " //$NON-NLS-1$
            + "was removed. Run infobase_sessions(action='list', projectName='" + projectName //$NON-NLS-1$
            + "', applicationId='" + applicationId + "') to see who still holds sessions.") //$NON-NLS-1$ //$NON-NLS-2$
                .put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, false)
                .put(KEY_UNREACHABLE_REASON, reason)
                .put(KEY_VERIFICATION, VERIFICATION_NOT_VERIFIABLE)
                .put(KEY_VERIFICATION_REASON, "The session list was not re-read after the " //$NON-NLS-1$
                    + "terminate command failed, so it is unknown whether the targeted session " //$NON-NLS-1$
                    + "was removed.") //$NON-NLS-1$
                .toJson();
    }

    /** Builds an unverified error after a later terminate command stops the sequence. */
    static String terminationSequenceStoppedResult(String projectName, String applicationId,
        int attemptedCount, String reason)
    {
        return ToolResult.errorWithUnknownMutationOutcome("Session termination stopped after " //$NON-NLS-1$
            + attemptedCount + " accepted attempt(s): " + reason //$NON-NLS-1$
            + " The session list was not re-read, so no attempted termination is reported as " //$NON-NLS-1$
            + "completed. Run infobase_sessions(action='list', projectName='" + projectName //$NON-NLS-1$
            + "', applicationId='" + applicationId + "') to see who still holds sessions.") //$NON-NLS-1$ //$NON-NLS-2$
                .put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, false)
                .put(KEY_UNREACHABLE_REASON, reason)
                .put("attemptedCount", attemptedCount) //$NON-NLS-1$
                .put(KEY_VERIFICATION, VERIFICATION_NOT_VERIFIABLE)
                .put(KEY_VERIFICATION_REASON, "The session list was not re-read because the " //$NON-NLS-1$
                    + "termination sequence stopped after a later command failed.") //$NON-NLS-1$
                .toJson();
    }

    /** Builds the terminate result from the accepted targets and the authoritative re-read. */
    static String terminationReadBackResult(String projectName, String applicationId,
        List<SessionInfo> attempted, ReadResult after)
    {
        return terminationReadBackResult(projectName, applicationId, attempted, after, 0);
    }

    /**
     * Reports a bulk terminate, including one stopped by its aggregate budget.
     *
     * <p>A budget stop is an error even though every attempted command succeeded: all=true asks
     * for a clear infobase, and a session the loop never reached still blocks an update.
     * Reporting success there would mislead exactly the caller this exists for.
     *
     * @param projectName target EDT project
     * @param applicationId resolved standalone-server application
     * @param attempted sessions whose terminate command was accepted
     * @param after the list re-read after the loop
     * @param notAttemptedCount selected sessions the loop never reached
     * @return the serialized tool result
     */
    static String terminationReadBackResult(String projectName, String applicationId,
        List<SessionInfo> attempted, ReadResult after, int notAttemptedCount)
    {
        if (notAttemptedCount > 0)
        {
            return bulkBudgetStoppedResult(projectName, applicationId, attempted, after,
                notAttemptedCount);
        }
        if (!after.isReadable())
        {
            return ToolResult.success().put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, true)
                .put("attemptedCount", attempted.size()) //$NON-NLS-1$
                .put(KEY_VERIFICATION, VERIFICATION_NOT_VERIFIABLE)
                .put(KEY_VERIFICATION_REASON, after.unreachableReason())
                .put(McpKeys.MESSAGE, "ibcmd accepted " + attempted.size() //$NON-NLS-1$
                    + " termination attempt(s), but the list could not be re-read to confirm they " //$NON-NLS-1$
                    + "are gone. List again before treating the infobase as clear.") //$NON-NLS-1$
                .toJson();
        }

        List<SessionInfo> gone = goneAmong(attempted, after);
        List<SessionInfo> stillPresent = stillPresentAmong(attempted, after);
        if (!stillPresent.isEmpty())
        {
            List<String> stillPresentIds = stillPresent.stream()
                .map(InfobaseSessionErrorProjection::identifier)
                .flatMap(Optional::stream)
                .toList();
            boolean onlyDesignerSessions = stillPresent.stream()
                .allMatch(InfobaseSessionsTool::isDesignerSession);
            String message = "ibcmd accepted every termination, but " + stillPresent.size() //$NON-NLS-1$
                + " of " + attempted.size() //$NON-NLS-1$
                + (onlyDesignerSessions ? " Designer session(s)" : " session(s)") //$NON-NLS-1$ //$NON-NLS-2$
                + " are still present after a terminate command that reported success" //$NON-NLS-1$
                + (stillPresentIds.isEmpty() ? "" : ": " + String.join(", ", stillPresentIds)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". " //$NON-NLS-1$
                + (onlyDesignerSessions
                    ? "Designer sessions are not treated as blockers by update_database. " //$NON-NLS-1$
                    : "Non-Designer sessions in that list still block a database update. ") //$NON-NLS-1$
                + "Run infobase_sessions(action='list', projectName='" + projectName //$NON-NLS-1$
                + "', applicationId='" + applicationId + "') to see who holds them."; //$NON-NLS-1$ //$NON-NLS-2$
            // Unlike an unread sequence stop, this re-read is evidence that nothing changed.
            ToolResult result = gone.isEmpty() ? ToolResult.error(message)
                : ToolResult.errorAfterMutation(message);
            return result.put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, true)
                .put(KEY_SESSIONS, errorSessionMaps(gone))
                .put("terminatedCount", gone.size()) //$NON-NLS-1$
                .put(KEY_VERIFICATION, VERIFICATION_MISMATCHED)
                .put(KEY_VERIFICATION_REASON, "The session list still reports them after a " //$NON-NLS-1$
                    + "terminate command that reported success.") //$NON-NLS-1$
                .toJson();
        }

        ToolResult result = ToolResult.success().put(McpKeys.ACTION, ACTION_TERMINATE)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_REACHABLE, true)
            .put(KEY_SESSIONS, sessionMaps(gone))
            .put("terminatedCount", gone.size()) //$NON-NLS-1$
            .put(KEY_VERIFICATION, VERIFICATION_VERIFIED);
        if (gone.stream().anyMatch(InfobaseSessionsTool::isDesignerSession))
        {
            return result.put(McpKeys.MESSAGE, "Terminated a session that reported app-id: " //$NON-NLS-1$
                + "Designer, confirmed gone by re-reading the session list. It may have been " //$NON-NLS-1$
                + "EDT's own update agent or a human Configurator. If it was EDT's, EDT re-creates " //$NON-NLS-1$
                + "its agent on its next connect and an update running at the moment of " //$NON-NLS-1$
                + "termination can fail; a person's Configurator session will simply have been " //$NON-NLS-1$
                + "closed.") //$NON-NLS-1$
                .toJson();
        }
        return result.put(McpKeys.MESSAGE, "Terminated " + gone.size() //$NON-NLS-1$
            + " non-agent infobase session(s), confirmed gone by re-reading the session " //$NON-NLS-1$
            + "list; the EDT Designer agent was not targeted.") //$NON-NLS-1$
            .toJson();
    }

    /** Builds the partial result for a bulk terminate stopped by its aggregate budget. */
    static String bulkBudgetStoppedResult(String projectName, String applicationId,
        List<SessionInfo> attempted, ReadResult after, int notAttemptedCount)
    {
        String message = "Session termination stopped after " + attempted.size() //$NON-NLS-1$
            + " accepted attempt(s) to keep this call bounded: " + notAttemptedCount //$NON-NLS-1$
            + " selected session(s) were not attempted. " //$NON-NLS-1$
            + "Re-run infobase_sessions(action='terminate', projectName='" + projectName //$NON-NLS-1$
            + "', applicationId='" + applicationId //$NON-NLS-1$ //$NON-NLS-2$
            + "', all=true, confirm=true) to continue with the rest."; //$NON-NLS-1$
        if (!after.isReadable())
        {
            // The commands were accepted but nothing re-read them, so no count is evidence.
            return ToolResult.errorWithUnknownMutationOutcome(message)
                .put(McpKeys.ACTION, ACTION_TERMINATE)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_REACHABLE, true)
                .put("attemptedCount", attempted.size()) //$NON-NLS-1$
                .put("notAttemptedCount", notAttemptedCount) //$NON-NLS-1$
                .put(KEY_VERIFICATION, VERIFICATION_NOT_VERIFIABLE)
                .put(KEY_VERIFICATION_REASON, after.unreachableReason()).toJson();
        }
        List<SessionInfo> gone = goneAmong(attempted, after);
        // Same rule as an ordinary read-back: only an observed absence claims a mutation.
        ToolResult result = gone.isEmpty() ? ToolResult.error(message)
            : ToolResult.errorAfterMutation(message);
        result = result.put(McpKeys.ACTION, ACTION_TERMINATE)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_REACHABLE, true)
            .put(KEY_SESSIONS, errorSessionMaps(gone))
            .put("attemptedCount", attempted.size()) //$NON-NLS-1$
            .put("notAttemptedCount", notAttemptedCount) //$NON-NLS-1$
            .put("terminatedCount", gone.size()); //$NON-NLS-1$
        if (gone.size() < attempted.size())
        {
            return result.put(KEY_VERIFICATION, VERIFICATION_MISMATCHED)
                .put(KEY_VERIFICATION_REASON, "The session list still reports " //$NON-NLS-1$
                    + (attempted.size() - gone.size())
                    + " of them after a terminate command that reported success.") //$NON-NLS-1$
                .toJson();
        }
        return result.put(KEY_VERIFICATION, VERIFICATION_VERIFIED).toJson();
    }

    /** The attempted sessions the re-read list no longer reports. */
    static List<SessionInfo> goneAmong(List<SessionInfo> attempted, ReadResult after)
    {
        List<SessionInfo> gone = new ArrayList<>();
        for (SessionInfo session : attempted)
        {
            if (!containsSessionId(after.sessions(), session.sessionId()))
            {
                gone.add(session);
            }
        }
        return gone;
    }

    /** The attempted sessions the re-read list still reports. */
    static List<SessionInfo> stillPresentAmong(List<SessionInfo> attempted, ReadResult after)
    {
        List<SessionInfo> stillPresent = new ArrayList<>();
        for (SessionInfo session : attempted)
        {
            if (containsSessionId(after.sessions(), session.sessionId()))
            {
                stillPresent.add(session);
            }
        }
        return stillPresent;
    }

    /** Whether a re-read list still reports the given session UUID. */
    static boolean containsSessionId(List<SessionInfo> sessions, String sessionId)
    {
        for (SessionInfo session : sessions)
        {
            if (session.sessionId() != null && session.sessionId().equalsIgnoreCase(sessionId))
            {
                return true;
            }
        }
        return false;
    }

    /** Resolves a UUID/numeric selector or filters all non-agent sessions. */
    static Selection selectSessions(List<SessionInfo> sessions, String requestedSessionId,
        boolean all)
    {
        if (all)
        {
            List<SessionInfo> selected = new ArrayList<>();
            for (SessionInfo session : sessions)
            {
                if (!isDesignerSession(session))
                {
                    selected.add(session);
                }
            }
            return Selection.of(selected);
        }
        for (SessionInfo session : sessions)
        {
            if (requestedSessionId.equalsIgnoreCase(session.sessionId()))
            {
                return Selection.of(List.of(session));
            }
            if (session.sessionNumber() != null
                && requestedSessionId.equals(session.sessionNumber().toString()))
            {
                if (isDesignerSession(session))
                {
                    return Selection.error("A Designer/configurator session may be terminated only " //$NON-NLS-1$
                        + "by its exact full UUID, not its numeric session-id."); //$NON-NLS-1$
                }
                return Selection.of(List.of(session));
            }
        }
        List<String> available = new ArrayList<>();
        for (SessionInfo session : sessions)
        {
            InfobaseSessionErrorProjection.identifier(session).ifPresent(available::add);
        }
        return Selection.error("Session '" + requestedSessionId //$NON-NLS-1$
            + "' was not found in the readable session list. Available session identifiers: " //$NON-NLS-1$
            + (available.isEmpty() ? "none" : String.join(", ", available)) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Describes an empty bulk selection from what the readable list actually contained. */
    static String emptySelectionMessage(List<SessionInfo> sessions)
    {
        if (sessions.stream().anyMatch(InfobaseSessionsTool::isDesignerSession))
        {
            return "A session reporting app-id: Designer was skipped because bulk termination " //$NON-NLS-1$
                + "never selects that kind."; //$NON-NLS-1$
        }
        return "There were no sessions to terminate."; //$NON-NLS-1$
    }

    /** Converts records to the stable JSON field names exposed by the tool. */
    static List<Map<String, Object>> sessionMaps(List<SessionInfo> sessions)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionInfo session : sessions)
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.sessionId()); //$NON-NLS-1$
            item.put("sessionNumber", session.sessionNumber() == null //$NON-NLS-1$
                ? JsonNull.INSTANCE : session.sessionNumber());
            item.put("applicationKind", session.applicationKind()); //$NON-NLS-1$
            item.put("userName", session.userName()); //$NON-NLS-1$
            item.put("host", session.host()); //$NON-NLS-1$
            item.put("startedAt", session.startedAt()); //$NON-NLS-1$
            item.put("lastActiveAt", session.lastActiveAt()); //$NON-NLS-1$
            item.put("applicationKindIsDesigner", isDesignerSession(session)); //$NON-NLS-1$
            result.add(item);
        }
        return result;
    }

    /** Converts session records to the non-personal fields permitted in error payloads. */
    static List<Map<String, Object>> errorSessionMaps(List<SessionInfo> sessions)
    {
        return InfobaseSessionErrorProjection.fields(sessions);
    }

    private static String displayId(SessionInfo session)
    {
        return session.sessionNumber() == null ? session.sessionId()
            : session.sessionId() + " (session-id " + session.sessionNumber() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Recognises the ambiguous Designer kind by both parsed kind flag and raw app-id. */
    static boolean isDesignerSession(SessionInfo session)
    {
        return session.applicationKindIsDesigner()
            || "Designer".equalsIgnoreCase(session.applicationKind()); //$NON-NLS-1$
    }

    private static ToolResult baseResult(String action, String projectName, IApplication application)
    {
        return ToolResult.success().put(McpKeys.ACTION, action)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, application.getId());
    }

    private static ToolResult baseError(String message, String projectName, IApplication application)
    {
        return ToolResult.error(message).put(McpKeys.ACTION, ACTION_TERMINATE)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, application.getId());
    }

    /** Project/application resolution outcome. */
    private static final class ResolvedApplication
    {
        final IApplication application;
        final String errorJson;

        private ResolvedApplication(IApplication application, String errorJson)
        {
            this.application = application;
            this.errorJson = errorJson;
        }

        static ResolvedApplication of(IApplication application)
        {
            return new ResolvedApplication(application, null);
        }

        static ResolvedApplication error(String errorJson)
        {
            return new ResolvedApplication(null, errorJson);
        }
    }

    /** Pure session-selection outcome used by validation tests and the live termination path. */
    static final class Selection
    {
        final List<SessionInfo> sessions;
        final String error;

        private Selection(List<SessionInfo> sessions, String error)
        {
            this.sessions = List.copyOf(sessions);
            this.error = error;
        }

        static Selection of(List<SessionInfo> sessions)
        {
            return new Selection(sessions, null);
        }

        static Selection error(String error)
        {
            return new Selection(List.of(), error);
        }
    }
}
