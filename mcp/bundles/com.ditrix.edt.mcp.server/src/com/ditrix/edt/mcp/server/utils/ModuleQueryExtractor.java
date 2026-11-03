/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;

/** Extracts standalone query literals from a detached parse of stored BSL text. */
public final class ModuleQueryExtractor
{
    private static final Pattern SELECT = Pattern.compile(
        "(?iu)^(?:SELECT|\u0412\u042b\u0411\u0420\u0410\u0422\u042c)(?![\\p{L}\\p{N}_])");
    private static final Pattern STRUCTURE = Pattern.compile(
        "(?iu)(?<![.\\p{L}\\p{N}_])(?:FROM|INTO|AS|\u0418\u0417|\u041f\u041e\u041c\u0415\u0421\u0422\u0418\u0422\u042c|\u041a\u0410\u041a)(?![\\p{L}\\p{N}_])");
    private static final Pattern SIMPLE_PROJECTION = Pattern.compile("^(?:[0-9]|\\*|&[\\p{L}_])");

    private ModuleQueryExtractor() { }

    public static List<Literal> extract(String source, String methodName)
    {
        IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleUtils.BSL_LOOKUP_URI);
        IParser parser = provider == null ? null : provider.get(IParser.class);
        if (parser == null)
            throw new IllegalStateException("BSL parser is unavailable. Install EDT BSL support or pass queryText.");
        return extract(source, methodName, parser);
    }

    static List<Literal> extract(String source, String methodName, IParser parser)
    {
        // A detached parse never reads the editor document or the live BM model.
        var parsed = parser.parse(new StringReader(source));
        if (!(parsed.getRootASTElement() instanceof Module module))
            throw new IllegalArgumentException("Could not parse stored BSL. Check read_module_source or pass queryText.");
        if (parsed.hasSyntaxErrors())
        {
            INode error = parsed.getSyntaxErrors().iterator().next();
            throw new IllegalArgumentException("Stored BSL has a syntax error at line " + error.getStartLine()
                + ". Fix it using read_module_source or pass queryText directly.");
        }
        EObject scope = module;
        if (methodName != null)
        {
            scope = BslModuleUtils.findMethod(module, methodName);
            if (scope == null)
                throw new IllegalArgumentException("Method '" + methodName
                    + "' not found. Use get_module_structure to choose a procedure/function name.");
        }
        List<Literal> result = new ArrayList<>();
        var contents = scope.eAllContents();
        while (contents.hasNext())
        {
            EObject object = contents.next();
            if (!(object instanceof StringLiteral literal)) continue;
            List<INode> nodes = NodeModelUtils.findNodesForFeature(literal, BslPackage.Literals.STRING_LITERAL__LINES);
            if (nodes.isEmpty()) continue;
            var text = new DefaultStringLiteralTextProvider(literal);
            String query = text.getText();
            var select = SELECT.matcher(query.stripLeading());
            if (!select.find()) continue;
            boolean adjacent = nodes.stream().filter(n -> n.getText().startsWith("\"")).count() > 1;
            String reason = adjacent || concatenated(literal) ? "dynamic"
                : hasStructure(query.stripLeading().substring(select.end())) ? null : "no query structure";
            Method method = EcoreUtil2.getContainerOfType(literal, Method.class);
            result.add(new Literal(method == null ? null : method.getName(), nodes.get(0).getOffset(),
                source, query, text, reason));
        }
        result.sort(Comparator.comparingInt(Literal::offset));
        return List.copyOf(result);
    }

    private static boolean concatenated(EObject literal)
    {
        EObject parent = literal.eContainer();
        return parent instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.PLUS;
    }

    private static boolean hasStructure(String projection)
    {
        // Keep quoted operands nonblank without exposing their keywords; comments remain whitespace.
        String masked = Pattern.compile("\"(?:\"\"|[^\"])*\"|//[^\\r\\n]*").matcher(projection)
            .replaceAll(match -> match.group().startsWith("\"") ? " _ " : " ").strip();
        if (SIMPLE_PROJECTION.matcher(masked).find()) return true;
        var structure = STRUCTURE.matcher(masked);
        return structure.find() && !masked.substring(0, structure.start()).isBlank();
    }

    public record Literal(String method, int offset, String source, String queryText,
        DefaultStringLiteralTextProvider text, String reason)
    {
        public Map<String, Object> location()
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("method", method);
            value.put("line", lineAt(source, offset));
            value.put("column", columnAt(source, offset));
            return value;
        }

        /** Native issue coordinates refer to decoded QL; return saved-module coordinates. */
        public Map<String, Object> mapIssue(Map<String, Object> issue)
        {
            Map<String, Object> value = new LinkedHashMap<>(issue);
            int queryOffset = number(issue.get("offset"), -1);
            int line = number(issue.get("line"), -1);
            int column = number(issue.get("column"), 1);
            if (line > 0) value.put("queryLine", line);
            if (issue.containsKey("column")) value.put("queryColumn", column);
            if (queryOffset >= 0) value.put("queryOffset", queryOffset);
            else if (line > 0) queryOffset = offsetAt(queryText, line, column);
            value.remove("line");
            value.remove("column");
            value.remove("offset");
            var mapped = queryOffset < 0 ? null : text.getLocation(queryOffset);
            if (mapped != null)
            {
                int moduleOffset = offset + mapped.getSecond();
                value.put("line", lineAt(source, moduleOffset));
                value.put("column", columnAt(source, moduleOffset));
                value.put("offset", moduleOffset);
            }
            return value;
        }
    }

    private static int number(Object value, int fallback)
    {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int offsetAt(String text, int line, int column)
    {
        int start = 0;
        for (int current = 1; current < line; current++)
        {
            start = text.indexOf('\n', start);
            if (start < 0) return -1;
            start++;
        }
        return Math.min(text.length(), start + column - 1);
    }

    private static int lineAt(String text, int offset)
    {
        int line = 1;
        for (int i = 0; i < offset; i++)
            if (text.charAt(i) == '\n' || text.charAt(i) == '\r'
                && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) line++;
        return line;
    }

    private static int columnAt(String text, int offset)
    {
        return offset - Math.max(text.lastIndexOf('\n', offset - 1), text.lastIndexOf('\r', offset - 1));
    }
}
