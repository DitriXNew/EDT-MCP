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
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IExpressionManager;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpressionDelegate;
import org.eclipse.debug.core.model.IWatchExpressionListener;
import org.eclipse.debug.core.model.IWatchExpressionResult;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Evaluates a BSL expression in the context of a suspended stack frame.
 *
 * <p>Uses the Eclipse {@link IWatchExpressionDelegate} mechanism: looks up the
 * delegate registered for the frame's debug model identifier and runs the
 * expression asynchronously, blocking the MCP request thread until the result
 * arrives or a short timeout fires. Returns a flat {@code {value, type}} or
 * {@code {error: ...}} on failure.
 */
public class EvaluateExpressionTool implements IMcpTool
{
    public static final String NAME = "evaluate_expression"; //$NON-NLS-1$
    private static final long EVAL_TIMEOUT_MS = 15_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Evaluate a BSL expression in the context of a suspended stack frame. " //$NON-NLS-1$
            + "Pass frameRef from wait_for_break and the expression text. " //$NON-NLS-1$
            + "WARNING: this executes arbitrary BSL code in the running 1C application."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("frameRef", "Stable frame reference from wait_for_break (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expression", "BSL expression to evaluate (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        long frameRef = parseLong(params.get("frameRef")); //$NON-NLS-1$
        String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$

        if (frameRef <= 0)
        {
            return ToolResult.error("frameRef is required").toJson(); //$NON-NLS-1$
        }
        if (expression == null || expression.isEmpty())
        {
            return ToolResult.error("expression is required").toJson(); //$NON-NLS-1$
        }

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IStackFrame frame = registry.getFrame(frameRef);
        if (frame == null)
        {
            return ToolResult.error("stale frameRef — call wait_for_break again").toJson(); //$NON-NLS-1$
        }

        try
        {
            String modelId = ((IDebugElement) frame).getModelIdentifier();
            IExpressionManager expressionManager = DebugPlugin.getDefault().getExpressionManager();
            IWatchExpressionDelegate delegate = expressionManager.newWatchExpressionDelegate(modelId);
            if (delegate == null)
            {
                return ToolResult.error("No watch expression delegate registered for model: " + modelId //$NON-NLS-1$
                        + ". This 1C debug model may not support expression evaluation.").toJson(); //$NON-NLS-1$
            }

            final AtomicReference<IWatchExpressionResult> resultRef = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);
            delegate.evaluateExpression(expression, frame, new IWatchExpressionListener()
            {
                @Override
                public void watchEvaluationFinished(IWatchExpressionResult result)
                {
                    resultRef.set(result);
                    latch.countDown();
                }
            });

            if (!latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                return ToolResult.error("evaluation timed out after " + EVAL_TIMEOUT_MS + "ms").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            IWatchExpressionResult result = resultRef.get();
            if (result == null)
            {
                return ToolResult.error("no result returned from delegate").toJson(); //$NON-NLS-1$
            }
            if (result.hasErrors())
            {
                StringBuilder errs = new StringBuilder();
                for (String e : result.getErrorMessages())
                {
                    if (errs.length() > 0) errs.append("; "); //$NON-NLS-1$
                    errs.append(e);
                }
                return ToolResult.error(errs.toString()).toJson();
            }

            IValue value = result.getValue();
            String stringValue;
            String type;
            try
            {
                stringValue = value != null ? value.getValueString() : null;
                type = value != null ? value.getReferenceTypeName() : "Undefined"; //$NON-NLS-1$
            }
            catch (DebugException de)
            {
                return ToolResult.error("Failed to read value: " + de.getMessage()).toJson(); //$NON-NLS-1$
            }
            return ToolResult.success()
                .put("value", stringValue) //$NON-NLS-1$
                .put("type", type) //$NON-NLS-1$
                .toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in evaluate_expression", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static long parseLong(String s)
    {
        if (s == null || s.isEmpty()) return -1L;
        try {
            double d = Double.parseDouble(s.trim());
            if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return -1L;
            return (long) d;
        } catch (NumberFormatException nfe) { return -1L; }
    }
}
