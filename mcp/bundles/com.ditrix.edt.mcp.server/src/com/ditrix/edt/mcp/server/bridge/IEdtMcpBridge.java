/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.bridge;

/**
 * Stable in-process entry point for other Eclipse bundles that need to discover
 * and call EDT-MCP tools without importing EDT-MCP implementation packages.
 * <p>
 * Consumers may look this service up by this interface's string name and invoke
 * its methods reflectively. Implementations and both methods therefore form a
 * public OSGi-facing contract.
 * <p>
 * The same instance is also published under the JDK function types
 * {@code java.util.function.BiFunction} ({@link #callTool}) and
 * {@code java.util.function.Supplier} ({@link #listTools}), both carrying the
 * service property {@link #SERVICE_PROPERTY} = {@link #SERVICE_PROPERTY_VALUE}.
 * That alias exists so a caller with no access to this package - an AI assistant
 * running a JShell snippet, another plugin, a script - can obtain a typed,
 * reflection-free handle:
 *
 * <pre>
 * var refs = ctx.getServiceReferences(java.util.function.BiFunction.class, "(edt.mcp.bridge=v1)");
 * var out = ctx.getService(refs.iterator().next()).apply("get_edt_version", "{}");
 * </pre>
 * <p>
 * This package is deliberately NOT in {@code Export-Package}, and adding it there
 * BREAKS THE BUILD. The test bundle is a FRAGMENT of this host; while the host
 * exports nothing, the fragment compiles against every host package, but as soon
 * as one package is exported Tycho derives the fragment's access rules from that
 * export list and every other host package becomes unresolvable. Nothing needs the
 * export: an OSGi service is found by the string class name whether or not the
 * requesting bundle is wired to the package, and consumers reach it either through
 * the JDK-type alias above or reflectively ({@code svc.getClass().getMethod(...)}),
 * never by compiling against this type.
 */
public interface IEdtMcpBridge
{
    /** Service property that marks every published alias of this bridge. */
    String SERVICE_PROPERTY = "edt.mcp.bridge"; //$NON-NLS-1$

    /** Value of {@link #SERVICE_PROPERTY} for this contract revision. */
    String SERVICE_PROPERTY_VALUE = "v1"; //$NON-NLS-1$

    /**
     * Lists registered tools as a compact JSON array of name/description objects.
     *
     * @return JSON array {@code [{"name":...,"description":...}]}
     */
    String listTools();

    /**
     * Calls a registered tool through the normal MCP {@code tools/call}
     * dispatcher.
     *
     * @param toolName exact registered tool name
     * @param argsJson JSON object containing the tool arguments; blank means an
     *            empty object
     * @return the same JSON-RPC response shape produced for MCP
     *         {@code tools/call}
     */
    String callTool(String toolName, String argsJson);
}
