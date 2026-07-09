/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugFrameResolver;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.VariableSerializer;

/**
 * Sets the value of a single BSL variable in a suspended stack frame.
 *
 * <p>This is a WRITE / side-effecting tool: the entered {@code value} is applied
 * as a BSL literal/expression live in the running 1C application through the
 * standard Eclipse {@link IVariable#setValue(String)} modification path. The 1C
 * debug variable model implements the platform {@code IValueModification} contract
 * verbatim: {@code supportsValueModification()} returns a per-variable
 * "modifiable" flag delivered by the debug protocol (an ordinary settable local
 * returns {@code true}; a genuinely read-only node returns {@code false}), and
 * {@code setValue(String)} routes the write through the running application's
 * runtime evaluation engine. No 1C-internal type is referenced — the write goes
 * through the plain Eclipse {@code IVariable} API, exactly like {@code get_variables}
 * reads. The target frame is addressed exactly like {@code get_variables} — by
 * {@code frameRef} (preferred, returned from {@code wait_for_break}) or
 * {@code threadId + frameIndex} — and the variable is located by
 * {@code variableName} (dot-separated for a nested member).
 *
 * <p>Read the current state first with {@code get_variables}; use
 * {@code evaluate_expression} to compute a value WITHOUT mutating anything (in BSL
 * an expression {@code X = 42} is a comparison that yields a Boolean, it does not
 * assign). Both model round-trips — the {@code verifyValue} pre-flight and the
 * {@code setValue} write — run inside a single bounded guard so a wedged debug
 * client can never block the MCP request thread indefinitely.
 *
 * <p>The modification is submitted asynchronously: the running application applies
 * it — and reports a value it rejects — on the debug evaluation thread after
 * {@code setValue} returns. The {@code value}/{@code type} echoed back is therefore
 * a best-effort immediate re-read that can still show the prior value; confirm the
 * applied result with a follow-up {@code get_variables}.
 */
public class SetVariableTool implements IMcpTool
{
    public static final String NAME = "set_variable"; //$NON-NLS-1$

    /** Input key: name (dot-path) of the variable to modify. */
    private static final String KEY_VARIABLE_NAME = "variableName"; //$NON-NLS-1$

    /** Input key: the new value, as a BSL literal/expression string. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** DTO/output key: the variable's serialised type. */
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$

    /** Upper bound on the blocking {@code setValue} call — mirrors evaluate_expression. */
    private static final long SET_TIMEOUT_MS = 15_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set a BSL variable's value in a suspended debug frame. " //$NON-NLS-1$
            + "WRITE/side-effect: EXECUTES the entered value as a BSL literal/expression live in the running " //$NON-NLS-1$
            + "1C application, mutating the variable. Address the frame by frameRef from wait_for_break " //$NON-NLS-1$
            + "(preferred) or threadId+frameIndex; name the variable (dot-path for a nested member). " //$NON-NLS-1$
            + "Read current values with get_variables first; use evaluate_expression to compute without mutating."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("frameRef", "Stable frame reference returned from wait_for_break (preferred)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Thread id (alternative to frameRef)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("frameIndex", "0-based frame index when using threadId") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_VARIABLE_NAME,
                "Name of the variable to set (dot-separated to reach a nested member)", true) //$NON-NLS-1$
            .stringProperty(KEY_VALUE,
                // The example ends in Дата(2025,1,1), the BSL Date() constructor; the Cyrillic is escaped
                // as unicode escapes because this description ships in tools/list (ASCII-safe under any tooling).
                "New value as a BSL literal/expression (e.g. 42, \"text\", True, " //$NON-NLS-1$
                    + "\u0414\u0430\u0442\u0430(2025,1,1))", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_VARIABLE_NAME, "The variable that was set") //$NON-NLS-1$
            .stringProperty(KEY_VALUE, "The variable's new value after the set (may be truncated)") //$NON-NLS-1$
            .stringProperty(KEY_TYPE, "BSL reference type name of the new value") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String argErr = JsonUtils.requireArguments(params, KEY_VARIABLE_NAME, KEY_VALUE);
        if (argErr != null)
        {
            return argErr;
        }

        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        String variableName = JsonUtils.extractStringArgument(params, KEY_VARIABLE_NAME);
        String value = JsonUtils.extractStringArgument(params, KEY_VALUE);

        DebugSessionRegistry registry = DebugSessionRegistry.get();

        try
        {
            DebugFrameResolver.Resolution fr = DebugFrameResolver.resolve(registry, frameRef, threadId, frameIndex);
            if (fr.error != null)
            {
                return fr.error;
            }
            IStackFrame frame = fr.frame;

            IVariable var = VariableSerializer.resolvePath(frame, variableName);
            if (var == null)
            {
                return ToolResult.error("variable not found: '" + variableName //$NON-NLS-1$
                    + "'. Use get_variables to list the variables visible in this frame " //$NON-NLS-1$
                    + "(use a dot-separated path to reach a nested member).").toJson(); //$NON-NLS-1$
            }

            if (!var.supportsValueModification())
            {
                return ToolResult.error("variable '" + variableName //$NON-NLS-1$
                    + "' is read-only and cannot be modified in this frame.").toJson(); //$NON-NLS-1$
            }

            String setErr = setValueBounded(var, variableName, value);
            if (setErr != null)
            {
                return setErr;
            }

            Map<String, Object> dto = VariableSerializer.serializeVariable(var, registry);
            return ToolResult.success()
                .put(KEY_VARIABLE_NAME, variableName)
                .put(KEY_VALUE, dto.get(KEY_VALUE))
                .put(KEY_TYPE, dto.get(KEY_TYPE))
                .toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in set_variable", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Optional pre-flight: rejects a value the debug model already knows it cannot
     * accept for this variable's type. A model that does not implement
     * {@code verifyValue} (throws) is treated as inconclusive — the authoritative
     * {@code setValue} attempt then decides — so this guard never turns a settable
     * variable into a false negative.
     *
     * <p>Invoked only from inside {@link #setValueBounded}'s bounded worker: like
     * {@code setValue}, {@code verifyValue} may round-trip to the (possibly wedged)
     * 1C debug client, so it must run under the same timeout — never on the MCP
     * request thread.
     *
     * @return the actionable error JSON when the value is invalid, or {@code null}
     *         when it is accepted (or the check is inconclusive)
     */
    private static String verifyValue(IVariable var, String variableName, String value)
    {
        boolean verified;
        try
        {
            verified = var.verifyValue(value);
        }
        catch (DebugException de) // NOSONAR model may not implement verifyValue: fall through to setValue
        {
            verified = true;
        }
        if (!verified)
        {
            return ToolResult.error("invalid value for variable '" + variableName + "': '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' is not accepted for this variable's type. Provide a valid BSL literal/expression.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Runs the {@link #verifyValue} pre-flight and the blocking
     * {@link IVariable#setValue(String)} write inside a single bounded guard
     * (mirrors evaluate_expression's latch/timeout) so a wedged debug client can
     * never block the MCP request thread indefinitely: BOTH model round-trips are
     * performed on one daemon worker and awaited for at most {@link #SET_TIMEOUT_MS}.
     *
     * @return the error JSON on timeout, on an invalid value (from the pre-flight),
     *         or on any failure of the write (a {@link DebugException} or an unchecked
     *         exception from the live 1C model); {@code null} on success
     */
    private static String setValueBounded(IVariable var, String variableName, String value)
        throws InterruptedException
    {
        final AtomicReference<String> resultRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try
            {
                String verifyErr = verifyValue(var, variableName, value);
                if (verifyErr != null)
                {
                    resultRef.set(verifyErr);
                    return;
                }
                var.setValue(value);
            }
            catch (DebugException de)
            {
                resultRef.set(ToolResult.error(unwrapMessage(de)).toJson());
            }
            catch (RuntimeException re)
            {
                // The 1C BslVariable model can throw an unchecked exception (NPE/IllegalStateException)
                // from a live round-trip instead of wrapping it in a DebugException. Surface it as an
                // actionable error so a failed write is never reported as a (null->) success.
                resultRef.set(ToolResult.error(re.getMessage() != null ? re.getMessage() : re.toString()).toJson());
            }
            finally
            {
                latch.countDown();
            }
        }, "mcp-set-variable"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();

        if (!latch.await(SET_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        {
            return ToolResult.error("set_variable timed out after " + SET_TIMEOUT_MS + "ms").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return resultRef.get();
    }

    /**
     * Extracts the most informative message from a {@link DebugException}: the
     * message of its wrapped status exception, then the status message, then the
     * exception's own message — so the caller sees the underlying 1C debug error
     * rather than a generic wrapper.
     */
    private static String unwrapMessage(DebugException de)
    {
        if (de.getStatus() != null)
        {
            Throwable cause = de.getStatus().getException();
            if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty())
            {
                return cause.getMessage();
            }
            String statusMessage = de.getStatus().getMessage();
            if (statusMessage != null && !statusMessage.isEmpty())
            {
                return statusMessage;
            }
        }
        return de.getMessage() != null ? de.getMessage() : de.toString();
    }
}
