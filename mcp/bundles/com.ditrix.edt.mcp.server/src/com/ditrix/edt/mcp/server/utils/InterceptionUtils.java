/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionServiceProvider;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Surfaces 1C configuration-extension METHOD interception in BSL code output: when
 * an extension adopts a base module and annotates a method with
 * {@code &Before}/{@code &After}/{@code &Around}/{@code &ChangeAndValidate} (rus.
 * {@code &Перед}/{@code &После}/{@code &Вместо}/{@code &ИзменениеИКонтроль}), the
 * code tools append a footer so a reader of EITHER side learns about the link:
 * <ul>
 *   <li><b>Base side</b> — reading a core method/module, the footer names the
 *       extension(s) that intercept it ("intercepted by extension X").</li>
 *   <li><b>Extension side</b> — reading an extension method/module, the footer names
 *       the core method(s) it intercepts ("intercepts core method Y").</li>
 * </ul>
 *
 * <h2>How it resolves</h2>
 * Via the platform's own {@link IModuleExtensionService}
 * ({@link IModuleExtensionServiceProvider#INSTANCE}) — the SAME service the EDT BSL
 * editor uses for interception hyperlinks/hover. {@code isExtensionModule} picks the
 * direction; {@code getSourceMethod} maps an extension method's annotation pragmas to
 * the core methods it targets; {@code getExtensionModules} is the inverse (the
 * extension modules adopting a given core module). All of this is best-effort: any
 * failure (service missing, model not yet indexed) yields {@code null} and the code
 * tool simply omits the footer rather than failing the read.
 */
public final class InterceptionUtils
{
    private static final String VIA_SEPARATOR = "` via `"; //$NON-NLS-1$

    private InterceptionUtils()
    {
        // Utility class
    }

    /**
     * A short blockquote footer for a SINGLE method, or {@code null} when the method
     * neither intercepts nor is intercepted (or the service is unavailable). Append it
     * at the bottom of {@code read_method_source} output.
     *
     * @param module the method's containing BSL module (may be {@code null})
     * @param method the method (may be {@code null})
     * @return the markdown footer (leading newline included) or {@code null}
     */
    public static String methodFooter(Module module, Method method)
    {
        if (module == null || method == null)
        {
            return null;
        }
        try
        {
            IModuleExtensionService svc = IModuleExtensionServiceProvider.INSTANCE.getModuleExtensionService();
            if (svc == null)
            {
                return null;
            }
            Set<String> lines = new LinkedHashSet<>();
            if (svc.isExtensionModule(module))
            {
                collectExtensionSide(svc, method, lines);
            }
            else
            {
                collectBaseSide(svc, module, method.getName(), lines);
            }
            if (lines.isEmpty())
            {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : lines)
            {
                sb.append("\n> ").append(line).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return sb.toString();
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("read_method_source: interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * A {@code ## Extension interception} section for a WHOLE module listing every
     * interception link involving its methods, or {@code null} when there are none
     * (or the service is unavailable). Append it at the bottom of
     * {@code read_module_source} / {@code get_module_structure} output.
     *
     * @param module the BSL module (may be {@code null})
     * @return the markdown section (leading newline included) or {@code null}
     */
    public static String moduleFooter(Module module)
    {
        if (module == null)
        {
            return null;
        }
        try
        {
            IModuleExtensionService svc = IModuleExtensionServiceProvider.INSTANCE.getModuleExtensionService();
            if (svc == null)
            {
                return null;
            }
            Set<String> lines = new LinkedHashSet<>();
            if (svc.isExtensionModule(module))
            {
                // This extension module -> the core methods its methods intercept.
                for (Method method : module.getMethods())
                {
                    Map<Pragma, Method> sources = svc.getSourceMethod(method);
                    if (sources == null)
                    {
                        continue;
                    }
                    for (Map.Entry<Pragma, Method> entry : sources.entrySet())
                    {
                        Method baseMethod = entry.getValue();
                        String baseName = baseMethod != null ? baseMethod.getName() : pragmaTarget(entry.getKey());
                        lines.add("`" + safeName(method) + "` -> intercepts core method `" + baseName //$NON-NLS-1$ //$NON-NLS-2$
                            + VIA_SEPARATOR + annotation(entry.getKey()) + "`"); //$NON-NLS-1$
                    }
                }
            }
            else
            {
                // Inverse: every extension module that adopts this base module; list
                // each of its methods that resolves back to a method here.
                Collection<Module> extensionModules = svc.getExtensionModules(module);
                if (extensionModules != null)
                {
                    for (Module extModule : extensionModules)
                    {
                        String extProject = projectName(extModule);
                        for (Method extMethod : extModule.getMethods())
                        {
                            Map<Pragma, Method> sources = svc.getSourceMethod(extMethod);
                            if (sources == null)
                            {
                                continue;
                            }
                            for (Map.Entry<Pragma, Method> entry : sources.entrySet())
                            {
                                Method baseMethod = entry.getValue();
                                String baseName = baseMethod != null ? baseMethod.getName() : pragmaTarget(entry.getKey());
                                lines.add("`" + baseName + "` <- intercepted by extension `" + extProject //$NON-NLS-1$ //$NON-NLS-2$
                                    + "`: `" + safeName(extMethod) + VIA_SEPARATOR + annotation(entry.getKey()) + "`"); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                        }
                    }
                }
            }
            if (lines.isEmpty())
            {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\n## Extension interception\n\n"); //$NON-NLS-1$
            for (String line : lines)
            {
                sb.append("- ").append(line).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return sb.toString();
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("module interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Extension-side: the core methods {@code extensionMethod} intercepts (one line
     * per annotation pragma that resolves to a core method).
     */
    private static void collectExtensionSide(IModuleExtensionService svc, Method extensionMethod, Set<String> lines)
    {
        Map<Pragma, Method> sources = svc.getSourceMethod(extensionMethod);
        if (sources == null)
        {
            return;
        }
        for (Map.Entry<Pragma, Method> entry : sources.entrySet())
        {
            Method baseMethod = entry.getValue();
            String baseName = baseMethod != null ? baseMethod.getName() : pragmaTarget(entry.getKey());
            lines.add("**Extension interception** — `" + safeName(extensionMethod) //$NON-NLS-1$
                + "` intercepts core method `" + baseName + VIA_SEPARATOR + annotation(entry.getKey()) + "`"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Base-side: the extension method(s) that intercept the core method named
     * {@code baseMethodName} declared in {@code baseModule}.
     */
    private static void collectBaseSide(IModuleExtensionService svc, Module baseModule, String baseMethodName,
        Set<String> lines)
    {
        Collection<Module> extensionModules = svc.getExtensionModules(baseModule);
        if (extensionModules == null)
        {
            return;
        }
        for (Module extModule : extensionModules)
        {
            Map<Pragma, Method> hits = svc.getExtensionMethods(extModule, baseMethodName);
            if (hits == null || hits.isEmpty())
            {
                continue;
            }
            String extProject = projectName(extModule);
            for (Map.Entry<Pragma, Method> entry : hits.entrySet())
            {
                lines.add("**Intercepted by extension** `" + extProject + "`: `" + safeName(entry.getValue()) //$NON-NLS-1$ //$NON-NLS-2$
                    + VIA_SEPARATOR + annotation(entry.getKey()) + "`"); //$NON-NLS-1$
            }
        }
    }

    /** The annotation symbol with a leading {@code &}, e.g. {@code &ChangeAndValidate}. */
    private static String annotation(Pragma pragma)
    {
        String symbol = pragma != null ? pragma.getSymbol() : null;
        return symbol != null && !symbol.isEmpty() ? "&" + symbol : "&?"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The target method name from a pragma value (a quoted name), unquoted; fallback {@code ?}. */
    private static String pragmaTarget(Pragma pragma)
    {
        String value = pragma != null ? pragma.getValue() : null;
        if (value == null || value.isEmpty())
        {
            return "?"; //$NON-NLS-1$
        }
        return value.replace("\"", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String safeName(Method method)
    {
        return method != null && method.getName() != null ? method.getName() : "?"; //$NON-NLS-1$
    }

    /**
     * The workspace project name owning the module, parsed from its resource URI
     * ({@code platform:/resource/<project>/src/...}); falls back to the URI's last
     * segment or {@code ?}.
     */
    private static String projectName(Module module)
    {
        Resource resource = module != null ? module.eResource() : null;
        URI uri = resource != null ? resource.getURI() : null;
        if (uri == null)
        {
            return "?"; //$NON-NLS-1$
        }
        if (uri.segmentCount() >= 2 && "resource".equals(uri.segment(0))) //$NON-NLS-1$
        {
            return uri.segment(1);
        }
        String last = uri.lastSegment();
        return last != null ? last : "?"; //$NON-NLS-1$
    }
}
