/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * Clean-room implementation. The idea of reporting the top-level literal keys of a
 * Structure passed to an outgoing qualified call is inspired by the
 * {@code edt_outgoing_structures} tool of keyfire/edt-bridge (Apache-2.0). No source
 * was copied: this class is written from the public EDT BSL AST model
 * ({@code com._1c.g5.v8.dt.bsl.model}).
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FunctionStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.util.BslUtil;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Best-effort read tool: for each OUTGOING qualified call in a module (or one method),
 * report the top-level literal keys of the {@code Структура}/{@code Structure} passed
 * as that call's first argument.
 * <p>
 * A qualified call {@code <var>.Method(arg)} is an {@link Invocation} whose method
 * access is a {@link DynamicFeatureAccess}. When the first argument is a local variable
 * reference ({@link StaticFeatureAccess}), the tool collects the top-level keys that are
 * inserted into that variable in the SAME method via
 * {@code <var>.Вставить("key",…)}/{@code <var>.Insert("key",…)}, and — when the variable
 * is seeded from a same-module helper ({@code <var> = Helper(...)}) — expands that helper
 * ONE level to gather the keys it inserts. The analysis is flow-insensitive and reports
 * literal keys only.
 * <p>
 * The result is conservative: a record is marked {@code partial} when a key source is not
 * a clean single-part literal (a non-literal key argument, a {@code Новый Структура("a,b")}
 * seed, an external/unknown helper, or a multi-part/multi-line string literal), so the key
 * list on such a record may be incomplete.
 * <p>
 * Clean-room from the public EDT BSL AST. The tool idea credits keyfire/edt-bridge's
 * {@code edt_outgoing_structures} (Apache-2.0); no source was copied.
 */
public class GetOutgoingStructuresTool implements IMcpTool
{
    public static final String NAME = "get_outgoing_structures"; //$NON-NLS-1$

    /** Input param: optional method name to scope the analysis to a single method. */
    private static final String KEY_METHOD = "method"; //$NON-NLS-1$

    /**
     * Input param: optional qualifier filter. Matches the {@code <var>} in
     * {@code <var>.Method(...)} by prefix or equality (case-insensitive).
     */
    private static final String KEY_QUALIFIER = "qualifier"; //$NON-NLS-1$

    /** Output key: the array of outgoing-structure records. */
    private static final String KEY_STRUCTURES = "structures"; //$NON-NLS-1$

    /** Output key: the number of records emitted. */
    private static final String KEY_STRUCTURE_COUNT = "structureCount"; //$NON-NLS-1$

    /** Output key: whether the record cap was hit. */
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    /** Per-record key: the call qualifier ({@code <var>}), omitted for a local structure. */
    private static final String KEY_REC_QUALIFIER = "qualifier"; //$NON-NLS-1$

    /** Per-record key: the enclosing method name. */
    private static final String KEY_REC_METHOD = "method"; //$NON-NLS-1$

    /** Per-record key: the call-site line. */
    private static final String KEY_REC_LINE = "line"; //$NON-NLS-1$

    /** Per-record key: the argument variable name (when the argument is a local var ref). */
    private static final String KEY_REC_ARG = "arg"; //$NON-NLS-1$

    /** Per-record key: the array of collected literal keys. */
    private static final String KEY_REC_KEYS = "keys"; //$NON-NLS-1$

    /** Per-record key: present ({@code true}) when helper expansion contributed keys. */
    private static final String KEY_REC_VIA_HELPER = "viaHelper"; //$NON-NLS-1$

    /** Per-record key: present ({@code true}) when a key source was unreliable. */
    private static final String KEY_REC_PARTIAL = "partial"; //$NON-NLS-1$

    /** BSL {@code Structure.Insert} method, Russian spelling. */
    private static final String INSERT_RU = "Вставить"; //$NON-NLS-1$

    /** BSL {@code Structure.Insert} method, English spelling. */
    private static final String INSERT_EN = "Insert"; //$NON-NLS-1$

    /** Maximum number of records returned. */
    private static final int MAX_RECORDS = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "For each outgoing qualified call in a BSL module (or one method), report the " + //$NON-NLS-1$
               "top-level literal keys of the Structure passed as its first argument " + //$NON-NLS-1$
               "(local .Insert keys plus a one-level same-module seed/template helper). " + //$NON-NLS-1$
               "Best-effort, literal keys only. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_outgoing_structures')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_METHOD,
                "Optional method name (case-insensitive) to scope to one method; " //$NON-NLS-1$
                    + "omit to scan the whole module") //$NON-NLS-1$
            .stringProperty(KEY_QUALIFIER,
                "Optional call-qualifier filter: keep only calls whose <var> in " //$NON-NLS-1$
                    + "<var>.Method(...) matches this by prefix or equality (case-insensitive)") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max records. Default: 100, max: 500") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty(KEY_STRUCTURES,
                "One record per outgoing qualified call, each with the collected literal keys") //$NON-NLS-1$
            .integerProperty(KEY_STRUCTURE_COUNT, "Number of records emitted") //$NON-NLS-1$
            .booleanProperty(KEY_TRUNCATED, "True when the record cap was hit") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String method = JsonUtils.extractStringArgument(params, KEY_METHOD);
        String qualifier = JsonUtils.extractStringArgument(params, KEY_QUALIFIER);
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, 100);

        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.MODULE_PATH);
        if (err != null)
        {
            return err;
        }

        final int maxResults = Pagination.clampLimit(limit, MAX_RECORDS);
        final String methodFilter = (method != null && !method.isEmpty()) ? method : null;
        final String qualifierFilter = (qualifier != null && !qualifier.isEmpty()) ? qualifier : null;

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(analyze(projectName, modulePath, methodFilter, qualifierFilter, maxResults));
            }
            catch (Exception e)
            {
                Activator.logError("Error collecting outgoing structures", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    /**
     * Resolves the project/module (and, when requested, the single method), then walks the
     * selected method(s) collecting one record per outgoing qualified call.
     */
    private String analyze(String projectName, String modulePath, String methodFilter,
        String qualifierFilter, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return ToolResult.error("Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Outgoing structures require BSL AST (EMF). Check EDT Error Log for details.").toJson(); //$NON-NLS-1$
        }

        List<Method> methods = new ArrayList<>();
        if (methodFilter != null)
        {
            Method one = BslModuleUtils.findMethod(module, methodFilter);
            if (one == null)
            {
                // This tool is ResponseType.JSON, so the raw (non-JSON) not-found body must ride
                // inside a parseable {"success":false,"error":"..."} envelope; a bare string would
                // make the JSON delivery path (JsonParser.parseString) throw and surface a
                // protocol-level error instead of a clean isError result.
                return ToolResult.error(
                    BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodFilter)).toJson();
            }
            methods.add(one);
        }
        else
        {
            for (Method m : module.allMethods())
            {
                methods.add(m);
            }
        }

        JsonArray records = new JsonArray();
        boolean truncated = false;
        for (Method m : methods)
        {
            if (records.size() >= limit)
            {
                truncated = true;
                break;
            }
            truncated |= collectMethodRecords(m, qualifierFilter, limit, records);
        }

        return ToolResult.success()
            .put(KEY_STRUCTURES, records)
            .put(KEY_STRUCTURE_COUNT, records.size())
            .put(KEY_TRUNCATED, truncated)
            .toJson();
    }

    /**
     * Walks one method's AST for outgoing qualified calls and appends a record for each,
     * up to {@code limit} total across the shared {@code records} array.
     *
     * @param method the method to scan
     * @param qualifierFilter optional prefix/equality filter on the call qualifier
     * @param limit the global record cap
     * @param records the shared accumulator (appended to, never reassigned)
     * @return {@code true} when the cap was reached while scanning this method
     */
    boolean collectMethodRecords(Method method, String qualifierFilter, int limit, JsonArray records)
    {
        String methodName = method.getName();
        for (Iterator<EObject> iter = method.eAllContents(); iter.hasNext();)
        {
            if (records.size() >= limit)
            {
                return true;
            }
            EObject obj = iter.next();
            if (!(obj instanceof Invocation))
            {
                continue;
            }
            Invocation inv = (Invocation)obj;
            DynamicFeatureAccess dfa = asQualifiedCall(inv);
            if (dfa == null)
            {
                continue;
            }
            // Skip the structure-building calls themselves (<var>.Вставить/Insert(...)): they
            // populate a local structure, they are not outgoing consumers of one. Emitting them
            // would double-report every structure (once as the consumer, once per insert).
            if (isInsert(dfa.getName()))
            {
                continue;
            }
            String qualifier = qualifierName(dfa);
            if (!qualifierMatches(qualifier, qualifierFilter))
            {
                continue;
            }
            // Only outgoing calls that actually carry a Структура argument (a local var ref)
            // are outgoing-structure records. A call with no argument, or whose first argument
            // is not a simple local var (e.g. Marker() / Метод("x")), is a plain call and MUST
            // NOT be emitted — otherwise every non-struct outgoing call becomes a bogus record.
            StaticFeatureAccess argVar = firstArgVar(inv);
            if (argVar == null)
            {
                continue;
            }
            records.add(buildRecord(inv, argVar, qualifier, methodName, method));
        }
        return false;
    }

    /**
     * Returns the {@link DynamicFeatureAccess} of a qualified call {@code <var>.Method(...)},
     * or {@code null} when the invocation is not a qualified call.
     *
     * @param inv the invocation
     * @return the dynamic feature access, or {@code null}
     */
    static DynamicFeatureAccess asQualifiedCall(Invocation inv)
    {
        FeatureAccess access = inv.getMethodAccess();
        return access instanceof DynamicFeatureAccess ? (DynamicFeatureAccess)access : null;
    }

    /**
     * Returns the qualifier ({@code <var>}) of {@code <var>.Method(...)} when the source of the
     * dynamic feature access is a {@link StaticFeatureAccess}, or {@code null} otherwise.
     *
     * @param dfa the dynamic feature access
     * @return the qualifier name, or {@code null} when the source is not a simple var ref
     */
    static String qualifierName(DynamicFeatureAccess dfa)
    {
        Expression source = dfa.getSource();
        return source instanceof StaticFeatureAccess ? ((StaticFeatureAccess)source).getName() : null;
    }

    /**
     * True when {@code qualifier} passes the optional filter. A {@code null} filter accepts
     * everything; otherwise the match is case-insensitive equality OR the qualifier starting
     * with the filter (prefix). A {@code null} qualifier only passes when there is no filter.
     *
     * @param qualifier the actual call qualifier (may be {@code null})
     * @param filter the requested filter (may be {@code null})
     * @return {@code true} when the call should be kept
     */
    static boolean qualifierMatches(String qualifier, String filter)
    {
        if (filter == null)
        {
            return true;
        }
        if (qualifier == null)
        {
            return false;
        }
        String lowerQualifier = qualifier.toLowerCase();
        String lowerFilter = filter.toLowerCase();
        return lowerQualifier.equals(lowerFilter) || lowerQualifier.startsWith(lowerFilter);
    }

    /**
     * Builds the JSON record for one outgoing qualified call that carries a local-variable
     * struct argument: qualifier, enclosing method, call-site line, the argument variable name,
     * and the collected literal keys (plus {@code viaHelper}/{@code partial} flags). Callers
     * MUST have already resolved a non-{@code null} {@code argVar} (calls with no struct argument
     * are dropped before reaching here).
     *
     * @param inv the invocation (the call site)
     * @param argVar the first-argument local variable reference (the passed Структура)
     * @param qualifier the call qualifier (may be {@code null})
     * @param methodName the enclosing method name
     * @param method the enclosing method (the scope for key collection)
     * @return the populated JSON record
     */
    private JsonObject buildRecord(Invocation inv, StaticFeatureAccess argVar, String qualifier,
        String methodName, Method method)
    {
        JsonObject rec = new JsonObject();
        if (qualifier != null)
        {
            rec.addProperty(KEY_REC_QUALIFIER, qualifier);
        }
        rec.addProperty(KEY_REC_METHOD, methodName);
        rec.addProperty(KEY_REC_LINE, BslModuleUtils.getStartLine(inv));
        rec.addProperty(KEY_REC_ARG, argVar.getName());

        KeyCollector collector = new KeyCollector();
        collectKeysForVariable(method, argVar.getName(), collector);

        JsonArray keys = new JsonArray();
        for (String key : collector.keys)
        {
            keys.add(key);
        }
        rec.add(KEY_REC_KEYS, keys);
        if (collector.viaHelper)
        {
            rec.addProperty(KEY_REC_VIA_HELPER, true);
        }
        if (collector.partial)
        {
            rec.addProperty(KEY_REC_PARTIAL, true);
        }
        return rec;
    }

    /**
     * Returns the first argument of {@code inv} when it is a local variable reference
     * ({@link StaticFeatureAccess}), or {@code null} when there is no argument or the first
     * argument is a different expression kind.
     *
     * @param inv the invocation
     * @return the first-argument variable reference, or {@code null}
     */
    static StaticFeatureAccess firstArgVar(Invocation inv)
    {
        List<Expression> args = inv.getParams();
        if (args == null || args.isEmpty())
        {
            return null;
        }
        Expression first = args.get(0);
        return first instanceof StaticFeatureAccess ? (StaticFeatureAccess)first : null;
    }

    /**
     * Collects the top-level literal keys inserted into the variable named {@code varName}
     * within {@code method}: every {@code <varName>.Вставить/Insert("key",…)} call, plus a
     * ONE-level expansion of a same-module seed/template helper when
     * {@code <varName> = Helper(...)} assigns from an in-module function.
     *
     * @param method the method to scan
     * @param varName the argument variable name
     * @param out the accumulator (keys, viaHelper, partial)
     */
    private void collectKeysForVariable(Method method, String varName, KeyCollector out)
    {
        for (Iterator<EObject> iter = method.eAllContents(); iter.hasNext();)
        {
            EObject obj = iter.next();
            if (obj instanceof Invocation)
            {
                collectInsertKey((Invocation)obj, varName, out);
            }
            else if (obj instanceof SimpleStatement)
            {
                collectHelperSeed(method, (SimpleStatement)obj, varName, out);
            }
        }
    }

    /**
     * If {@code inv} is {@code <varName>.Вставить/Insert("key",…)}, extracts the literal key
     * from the first argument and adds it to {@code out}. A non-literal first argument or a
     * multi-part/multi-line literal is skipped and flags {@code out.partial}.
     *
     * @param inv the invocation
     * @param varName the target variable name
     * @param out the accumulator
     */
    private void collectInsertKey(Invocation inv, String varName, KeyCollector out)
    {
        DynamicFeatureAccess dfa = asQualifiedCall(inv);
        if (dfa == null || !isInsert(dfa.getName()))
        {
            return;
        }
        String source = qualifierName(dfa);
        if (source == null || !source.equalsIgnoreCase(varName))
        {
            return;
        }
        List<Expression> args = inv.getParams();
        if (args == null || args.isEmpty())
        {
            return;
        }
        addLiteralKey(args.get(0), out);
    }

    /**
     * If {@code stmt} is a seed assignment {@code <varName> = Helper(...)} whose right side is
     * an unqualified call to a resolvable same-module function, expands that helper ONE level:
     * collects the keys it inserts into its own returned structure. An external/unknown helper
     * flags {@code out.partial}; a helper that contributes keys flags {@code out.viaHelper}.
     *
     * <p>
     * A structure-constructor seed ({@code <varName> = Новый Структура("a,b")} — an
     * {@link OperatorStyleCreator}, or {@code <varName> = Новый("Структура", "a,b")} — a
     * {@link FunctionStyleCreator}) carries its keys in the constructor argument string, which
     * this tool does not parse. When such a seed has a non-empty constructor argument list, the
     * record is flagged {@code partial} so the caller knows the reported key list is a lower
     * bound (a bare no-argument {@code Новый Структура} carries no keys and is NOT flagged).
     *
     * @param outerMethod the method containing {@code stmt} (used to reach the module for helper lookup)
     * @param stmt the assignment statement
     * @param varName the target variable name
     * @param out the accumulator
     */
    void collectHelperSeed(Method outerMethod, SimpleStatement stmt, String varName, KeyCollector out)
    {
        Expression left = stmt.getLeft();
        if (!(left instanceof StaticFeatureAccess)
            || !varName.equalsIgnoreCase(((StaticFeatureAccess)left).getName()))
        {
            return;
        }
        Expression right = stmt.getRight();
        if (right instanceof OperatorStyleCreator)
        {
            // Новый Структура("Дата,Сумма"): constructor-string keys are not parsed here.
            if (!((OperatorStyleCreator)right).getParams().isEmpty())
            {
                out.partial = true;
            }
            return;
        }
        if (right instanceof FunctionStyleCreator)
        {
            // Новый("Структура", "Дата,Сумма"): constructor-string keys are not parsed here.
            if (((FunctionStyleCreator)right).getParamsExpression() != null)
            {
                out.partial = true;
            }
            return;
        }
        if (!(right instanceof Invocation))
        {
            return;
        }
        Invocation call = (Invocation)right;
        FeatureAccess access = call.getMethodAccess();
        if (!(access instanceof StaticFeatureAccess))
        {
            // A qualified seed (e.g. Common.Helper(...)) is an external helper: cannot expand.
            if (access instanceof DynamicFeatureAccess)
            {
                out.partial = true;
            }
            return;
        }
        String helperName = ((StaticFeatureAccess)access).getName();
        Module module = moduleOf(outerMethod);
        Method helper = module != null ? BslModuleUtils.findMethod(module, helperName) : null;
        if (helper == null)
        {
            // Unknown/external helper: the returned keys are not visible here.
            out.partial = true;
            return;
        }
        expandHelperOneLevel(helper, out);
    }

    /**
     * Expands a same-module helper ONE level: finds the local variable it returns and collects
     * the top-level literal keys inserted into that variable inside the helper. A helper calling
     * another helper is NOT recursed (the nested seed only flags {@code partial}).
     *
     * @param helper the resolved same-module helper method
     * @param out the accumulator (keys collected here also set {@code viaHelper})
     */
    private void expandHelperOneLevel(Method helper, KeyCollector out)
    {
        String returnedVar = returnedVariableName(helper);
        if (returnedVar == null)
        {
            // Cannot tell which local is returned: be conservative.
            out.partial = true;
            return;
        }
        int before = out.keys.size();
        for (Iterator<EObject> iter = helper.eAllContents(); iter.hasNext();)
        {
            EObject obj = iter.next();
            if (obj instanceof Invocation)
            {
                collectInsertKey((Invocation)obj, returnedVar, out);
            }
        }
        if (out.keys.size() > before)
        {
            out.viaHelper = true;
        }
    }

    /**
     * Returns the name of the local variable a helper returns (the {@code <var>} in
     * {@code Возврат <var>;}/{@code Return <var>;}), or {@code null} when the helper has no
     * such simple return. Used to know which variable's {@code .Insert} keys form the
     * helper's produced structure.
     *
     * @param helper the helper method
     * @return the returned variable name, or {@code null}
     */
    static String returnedVariableName(Method helper)
    {
        String last = null;
        for (Iterator<EObject> iter = helper.eAllContents(); iter.hasNext();)
        {
            EObject obj = iter.next();
            if (obj instanceof ReturnStatement)
            {
                Expression expr = ((ReturnStatement)obj).getExpression();
                if (expr instanceof StaticFeatureAccess)
                {
                    last = ((StaticFeatureAccess)expr).getName();
                }
            }
        }
        return last;
    }

    /**
     * Reads the single clean literal key from an {@code .Insert} first argument and adds it to
     * {@code out}. A non-{@link StringLiteral} argument or a multi-part/multi-line literal is
     * skipped and flags {@code out.partial}.
     *
     * @param keyArg the first argument of the {@code .Insert} call
     * @param out the accumulator
     */
    static void addLiteralKey(Expression keyArg, KeyCollector out)
    {
        if (!(keyArg instanceof StringLiteral))
        {
            // A computed/variable key: cannot report it literally.
            out.partial = true;
            return;
        }
        String key = singleLiteralOrNull((StringLiteral)keyArg);
        if (key == null)
        {
            // A multi-part/multi-line literal is unreliable — skip it.
            out.partial = true;
            return;
        }
        out.keys.add(key);
    }

    /**
     * Returns the single clean content of a {@link StringLiteral} (exactly one part), or
     * {@code null} when the literal has zero or more than one part (a multi-part/multi-line
     * literal is treated as unreliable). Reads the content via
     * {@link BslUtil#getStringLiteralContent(StringLiteral, boolean)} — {@link StringLiteral}
     * has no {@code getValue()}.
     *
     * @param literal the string literal
     * @return the single key, or {@code null} when it is not a clean single-part literal
     */
    static String singleLiteralOrNull(StringLiteral literal)
    {
        List<String> parts = BslUtil.getStringLiteralContent(literal, false);
        if (parts == null || parts.size() != 1)
        {
            return null;
        }
        return parts.get(0);
    }

    /**
     * True when {@code name} is the BSL {@code Structure.Insert} method in either language
     * spelling ({@code Вставить}/{@code Insert}), matched case-insensitively. Uses
     * {@code equalsIgnoreCase} on the real 1C keyword — not a regex.
     *
     * @param name the invoked method name (may be {@code null})
     * @return {@code true} for a Structure-insert call
     */
    static boolean isInsert(String name)
    {
        return INSERT_RU.equalsIgnoreCase(name) || INSERT_EN.equalsIgnoreCase(name);
    }

    /**
     * Returns the {@link Module} that contains {@code method}, by climbing its containers.
     *
     * @param method the method
     * @return the containing module, or {@code null} when none is found
     */
    private static Module moduleOf(Method method)
    {
        EObject container = method.eContainer();
        while (container != null && !(container instanceof Module))
        {
            container = container.eContainer();
        }
        return container instanceof Module ? (Module)container : null;
    }

    /**
     * Mutable accumulator threaded through key collection: the ordered keys plus the
     * {@code viaHelper} (helper expansion contributed keys) and {@code partial} (a key source
     * was unreliable) flags.
     */
    static final class KeyCollector
    {
        final List<String> keys = new ArrayList<>();
        boolean viaHelper;
        boolean partial;
    }
}
