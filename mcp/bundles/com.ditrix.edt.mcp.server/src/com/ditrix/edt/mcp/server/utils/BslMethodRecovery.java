/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextSyntaxDiagnostic;

import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.MethodSpan;

/** Saved-source evidence for method declarations missing from EDT's model. */
public final class BslMethodRecovery
{
    private BslMethodRecovery()
    {
    }

    private static IParseResult parseResult(Resource resource)
    {
        return resource instanceof XtextResource xtext ? xtext.getParseResult() : null;
    }

    /** Checks syntax only. Linking and validation diagnostics do not imply a partial tree. */
    public static boolean isParseUnverified(Resource resource)
    {
        IParseResult parse = parseResult(resource);
        return parse == null || parse.hasSyntaxErrors();
    }

    public record SyntaxIssue(int line, String message)
    {
    }

    /**
     * The first syntax diagnostic EDT keeps on the resource. Not the raw grammar's errors: EDT drops
     * the "expected ';'" before a preprocessor end (#EndRegion, #EndIf), as the platform does.
     */
    public static SyntaxIssue firstSyntaxIssue(Resource resource)
    {
        if (resource == null)
        {
            return null;
        }
        Resource.Diagnostic first = null;
        for (Resource.Diagnostic error : resource.getErrors())
        {
            if (error instanceof XtextSyntaxDiagnostic && (first == null || error.getLine() < first.getLine()))
            {
                first = error;
            }
        }
        return first == null ? null : new SyntaxIssue(first.getLine(), first.getMessage());
    }

    public record TextScan(List<String> lines, List<String> masked, List<MethodSpan> spans)
    {
        public MethodSpan find(String name)
        {
            for (MethodSpan span : spans)
            {
                if (span.name.equalsIgnoreCase(name))
                {
                    return span;
                }
            }
            return null;
        }

        public boolean exported(MethodSpan span)
        {
            return BslModuleUtils.isExportDeclaration(masked, span.declarationLine, span.endLine);
        }

        public List<String> names()
        {
            return spans.stream().map(span -> span.name).toList();
        }

        public String region(MethodSpan span)
        {
            return BslModuleUtils.findRegionForLine(masked, span.declarationLine + 1);
        }

        public Map<Integer, String> regions(List<MethodSpan> recovered)
        {
            return BslModuleUtils.findMethodRegions(masked, recovered);
        }

        public List<String> availableNames(List<String> modelNames)
        {
            List<String> names = new ArrayList<>(modelNames);
            Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            modelNames.stream().filter(Objects::nonNull).forEach(seen::add);
            for (MethodSpan span : spans)
            {
                if (seen.add(span.name))
                {
                    names.add(span.name);
                }
            }
            return names;
        }
    }

    public static TextScan scan(List<String> lines)
    {
        List<String> masked = BslSyntaxChecker.maskLiteralsAndComments(lines);
        return new TextScan(lines, masked, BslModuleUtils.findMethodSpansViaText(lines, masked));
    }

    public static TextScan scanSavedModule(IProject project, String modulePath)
    {
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (file == null || !file.exists())
        {
            return null;
        }
        try
        {
            return scan(BslModuleUtils.readFileLines(file));
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not scan saved BSL module '" + modulePath + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    public static String recoveredSpanNotice(MethodSpan span)
    {
        return "Span recovered from saved source text because EDT's parse of the module is incomplete." //$NON-NLS-1$
            + (span.complete ? "" : " The matching method terminator was not found."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String missingMethodForAnalysis(Module module, IProject project, String modulePath, String name)
    {
        return missingMethodForAnalysis(module, modulePath, name, scanSavedModule(project, modulePath));
    }

    public static String missingMethodForAnalysis(Module module, String modulePath, String name, TextScan text)
    {
        List<String> names = BslModuleUtils.methodNames(module);
        if (text == null)
        {
            return BslModuleUtils.buildTextMethodNotFoundResponse(name, modulePath, names);
        }
        MethodSpan span = text.find(name);
        List<String> available = text.availableNames(names);
        if (span == null)
        {
            return BslModuleUtils.buildTextMethodNotFoundResponse(name, modulePath, available);
        }
        StringBuilder message = new StringBuilder("Method '").append(name).append("' in ").append(modulePath) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" was found in saved source at lines ").append(span.startLine + 1).append('-') //$NON-NLS-1$
            .append(span.endLine + 1).append(". ").append(recoveredSpanNotice(span)) //$NON-NLS-1$
            .append(" Use read_method_source to inspect it, then fix or refresh the EDT parse and retry the analysis.\n\n"); //$NON-NLS-1$
        BslModuleUtils.appendAvailableMethods(message, available);
        return message.toString();
    }
}
