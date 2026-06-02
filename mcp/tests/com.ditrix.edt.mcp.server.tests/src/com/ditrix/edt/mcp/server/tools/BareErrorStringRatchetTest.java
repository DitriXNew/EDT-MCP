/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * Ratchet for CLAUDE.md rule #8: a tool must surface failures through
 * {@code ToolResult.error(...)}, never by returning a bare
 * <code>"Error: ..."</code> string from {@code execute()} (or a helper that
 * feeds {@code execute()}).
 * <p>
 * The check scans the compiled constant pool of every registered
 * {@link IMcpTool} and flags any <em>string literal</em> (a CONSTANT_String
 * entry, i.e. a real {@code "..."} in source — not a class/method name, a
 * descriptor, or a Javadoc comment, which never become CONSTANT_String
 * entries) that begins with the exact token {@code "Error:"}.
 * <p>
 * The {@code "Error:"} prefix (with the trailing colon) is the precise marker
 * of the anti-pattern and avoids the known false positives:
 * <ul>
 * <li>log messages like {@code "Error reading X"} start with {@code "Error "}
 * (space, no colon);</li>
 * <li>the {@code "Errors:\n"} markdown header starts with {@code "Errors:"}
 * (the 6th char is {@code 's'}, not {@code ':'});</li>
 * <li>the bare {@code "Error"} placeholder has no colon at all.</li>
 * </ul>
 * Only an actual {@code return "Error: ..."} (the anti-pattern) matches.
 * <p>
 * RATCHET: {@link #ALLOWLIST} is seeded EMPTY because the tree is clean. A new
 * bare-error string fails this test until it is converted to
 * {@code ToolResult.error(...)} (preferred) or — deliberately — allow-listed.
 */
public class BareErrorStringRatchetTest
{
    /** The anti-pattern marker: a string literal starting with this exact token. */
    private static final String BARE_ERROR_PREFIX = "Error:"; //$NON-NLS-1$

    /**
     * Deliberately tolerated bare-error literals, keyed by
     * {@code SimpleClassName + "::" + literal}. Seeded EMPTY (the tree is clean
     * after the WriteModuleSourceTool fix). Keep it empty — convert new
     * violations to {@code ToolResult.error(...)} instead of growing this list.
     */
    private static final Set<String> ALLOWLIST = new HashSet<>();

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void noToolReturnsABareErrorString() throws IOException
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> violations = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> toolClass = tool.getClass();
            for (String literal : readStringConstants(toolClass))
            {
                if (literal.startsWith(BARE_ERROR_PREFIX))
                {
                    String key = toolClass.getSimpleName() + "::" + literal; //$NON-NLS-1$
                    if (!ALLOWLIST.contains(key))
                    {
                        violations.add(key);
                    }
                }
            }
        }

        assertTrue("Tools return a bare \"Error:\" string instead of " //$NON-NLS-1$
            + "ToolResult.error(...).toJson() (CLAUDE.md rule #8). " //$NON-NLS-1$
            + "Convert each to the structured error contract: " //$NON-NLS-1$
            + violations, violations.isEmpty());
    }

    /**
     * Reads every CONSTANT_String literal (true {@code "..."} source string)
     * from the given class's compiled {@code .class} bytes. Class/method names,
     * field/method descriptors and Javadoc never appear as CONSTANT_String, so
     * they are excluded by construction.
     */
    private static List<String> readStringConstants(Class<?> clazz) throws IOException
    {
        String resource = clazz.getSimpleName() + ".class"; //$NON-NLS-1$
        try (InputStream raw = clazz.getResourceAsStream(resource))
        {
            assertTrue("class resource not found for " + clazz.getName() //$NON-NLS-1$
                + " (expected " + resource + " next to the class)", raw != null); //$NON-NLS-1$ //$NON-NLS-2$
            try (DataInputStream in = new DataInputStream(raw))
            {
                return parseStringConstants(in);
            }
        }
    }

    /**
     * Minimal constant-pool walker. Reads the JVM class-file header and the
     * constant pool, collecting the UTF-8 text of every CONSTANT_String (tag 8)
     * entry — those are the actual string literals.
     */
    private static List<String> parseStringConstants(DataInputStream in) throws IOException
    {
        int magic = in.readInt();
        assertTrue("not a class file (bad magic)", magic == 0xCAFEBABE); //$NON-NLS-1$
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version

        int constantPoolCount = in.readUnsignedShort();
        String[] utf8 = new String[constantPoolCount];
        // Index of the Utf8 entry each CONSTANT_String (tag 8) points at.
        int[] stringRef = new int[constantPoolCount];

        for (int i = 1; i < constantPoolCount; i++)
        {
            int tag = in.readUnsignedByte();
            switch (tag)
            {
                case 1: // CONSTANT_Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7: // CONSTANT_Class
                case 8: // CONSTANT_String
                case 16: // CONSTANT_MethodType
                case 19: // CONSTANT_Module
                case 20: // CONSTANT_Package
                {
                    int ref = in.readUnsignedShort();
                    if (tag == 8)
                    {
                        stringRef[i] = ref;
                    }
                    break;
                }
                case 15: // CONSTANT_MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                case 3: // CONSTANT_Integer
                case 4: // CONSTANT_Float
                case 9: // CONSTANT_Fieldref
                case 10: // CONSTANT_Methodref
                case 11: // CONSTANT_InterfaceMethodref
                case 12: // CONSTANT_NameAndType
                case 17: // CONSTANT_Dynamic
                case 18: // CONSTANT_InvokeDynamic
                    in.readInt();
                    break;
                case 5: // CONSTANT_Long
                case 6: // CONSTANT_Double
                    in.readLong();
                    i++; // 8-byte constants take two pool slots
                    break;
                default:
                    throw new IOException("unknown constant pool tag: " + tag); //$NON-NLS-1$
            }
        }

        List<String> literals = new ArrayList<>();
        for (int i = 1; i < constantPoolCount; i++)
        {
            int ref = stringRef[i];
            if (ref > 0 && ref < utf8.length && utf8[ref] != null)
            {
                literals.add(utf8[ref]);
            }
        }
        return literals;
    }
}
