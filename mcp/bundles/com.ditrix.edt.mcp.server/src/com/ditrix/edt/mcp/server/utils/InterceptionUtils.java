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
import java.util.TreeMap;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionServiceProvider;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.lcore.util.CaseInsensitiveString;
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
 * the core methods it targets; {@code getExtensionModules} finds the adopting
 * modules. The base-side footer reads annotation targets without needing an
 * extension-to-core source lookup. All of this is best-effort: any
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

    private static IModuleExtensionService extensionService()
    {
        return IModuleExtensionServiceProvider.INSTANCE.getModuleExtensionService();
    }

    /** Reads an adopted module's source in the base model while the extension read is active. */
    public static <T> T readSourceModule(IProject project, Module module, String taskName,
        BiFunction<IProject, Module, T> read)
    {
        IProject base = ExtensionOriginUtils.resolveBaseProject(project);
        if (base == null || base.equals(project))
        {
            return null;
        }
        IModuleExtensionService service = extensionService();
        if (service == null || !service.isExtensionModule(module))
        {
            return null;
        }
        return ReadToolExecution.readModel(base, taskName, () -> {
            Module source = service.getSourceModule(module);
            if (source == null || source.eIsProxy())
            {
                throw Refusals.state("The adopted module's source is unavailable in base project '" + base.getName() //$NON-NLS-1$
                    + "'. Open and index the base project, then retry."); //$NON-NLS-1$
            }
            return read.apply(base, source);
        });
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
            return methodFooter(module, method, extensionService());
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("read_method_source: interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    static String methodFooter(Module module, Method method, IModuleExtensionService svc)
    {
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
            return moduleFooter(module, extensionService());
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("module interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    static String moduleFooter(Module module, IModuleExtensionService svc)
    {
        if (svc == null)
        {
            return null;
        }
        Set<String> lines = new LinkedHashSet<>();
        if (svc.isExtensionModule(module))
        {
            collectModuleExtensionSide(svc, module, lines);
        }
        else
        {
            collectModuleBaseSide(svc, module, lines);
        }
        return lines.isEmpty() ? null : buildFooterSection(lines);
    }

    /**
     * Module extension-side: for an extension {@code module}, lists the core methods
     * each of its methods intercepts (one line per resolving annotation pragma).
     */
    private static void collectModuleExtensionSide(IModuleExtensionService svc, Module module, Set<String> lines)
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
                String baseName = baseMethodName(entry);
                lines.add("`" + safeName(method) + "` -> intercepts core method `" + baseName //$NON-NLS-1$ //$NON-NLS-2$
                    + VIA_SEPARATOR + annotation(entry.getKey()) + "`"); //$NON-NLS-1$
            }
        }
    }

    /**
     * The method footer's forward lookup searches the adopting module for its
     * annotation target. For a whole module, match those same targets in one pass:
     * the native per-name lookup would rescan every adopting module for each base
     * method. Unlike getSourceMethod, getSourceMethodNames does not require the
     * extension's reverse source module to be resolved.
     */
    private static void collectModuleBaseSide(IModuleExtensionService svc, Module module, Set<String> lines)
    {
        Collection<Module> extensionModules = svc.getExtensionModules(module);
        if (extensionModules == null || extensionModules.isEmpty())
        {
            return;
        }
        // Match the forward lookup's equalsIgnoreCase, including when the SDK's
        // CaseInsensitiveString hash differs for equivalent Unicode spellings.
        Map<String, String> baseNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Method method : module.allMethods())
        {
            String name = method.getName();
            if (name != null && !name.isEmpty())
            {
                baseNames.putIfAbsent(name, name);
            }
        }
        for (Module extModule : extensionModules)
        {
            collectAdoptingModuleLinks(svc, extModule, baseNames, lines);
        }
    }

    private static void collectAdoptingModuleLinks(IModuleExtensionService svc, Module extModule,
        Map<String, String> baseNames, Set<String> lines)
    {
        String extProject = projectName(extModule);
        for (Method extMethod : extModule.allMethods())
        {
            Map<CaseInsensitiveString, Collection<Pragma>> targets = svc.getSourceMethodNames(extMethod);
            if (targets == null)
            {
                continue;
            }
            for (Map.Entry<CaseInsensitiveString, Collection<Pragma>> target : targets.entrySet())
            {
                String baseName = baseNames.get(target.getKey().getString());
                Collection<Pragma> pragmas = target.getValue();
                if (baseName == null || pragmas == null)
                {
                    continue;
                }
                for (Pragma pragma : pragmas)
                {
                    lines.add("`" + baseName + "` <- intercepted by extension `" + extProject //$NON-NLS-1$ //$NON-NLS-2$
                        + "`: `" + safeName(extMethod) + VIA_SEPARATOR + annotation(pragma) + "`"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    /**
     * The resolved core-method name for a source-map entry: the target method's name
     * when it resolved, otherwise the annotation pragma's quoted target.
     */
    private static String baseMethodName(Map.Entry<Pragma, Method> entry)
    {
        Method baseMethod = entry.getValue();
        return baseMethod != null ? baseMethod.getName() : pragmaTarget(entry.getKey());
    }

    /** Assembles the {@code ## Extension interception} markdown section from collected lines. */
    private static String buildFooterSection(Set<String> lines)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Extension interception\n\n"); //$NON-NLS-1$
        for (String line : lines)
        {
            sb.append("- ").append(line).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
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
            String baseName = baseMethodName(entry);
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
