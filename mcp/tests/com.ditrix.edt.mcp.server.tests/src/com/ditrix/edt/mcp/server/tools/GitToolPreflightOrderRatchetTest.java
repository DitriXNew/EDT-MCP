/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.GitTool;

/**
 * Ratchet for the ORDER of the two gates {@code GitTool.execute()} runs: the read-only pre-flight
 * ({@code preflightRefusal}, which refuses an operand outside the work tree and a stored remote
 * whose credential cannot be masked - issue #314) must run BEFORE the destructive-consent gate
 * ({@code requireConsentFor}).
 * <p>
 * Why a ratchet at all: {@code execute()} needs a resolved EDT project, so no unit test can drive
 * it, and the consent gate may ASK a human - which a headless run must never trigger. Every
 * behavioural case therefore drives {@code preflightRefusal} directly. That leaves the WIRING
 * unpinned: delete the call from {@code execute()}, or slide it below the consent gate, and the
 * whole suite stays green while a poisoned remote prints verbatim - or a human is prompted for a
 * command that can never run. {@code execute()} is a long method that will be refactored again, so
 * the rule it states in its own comment ("Consent LAST, after every read-only check has passed")
 * gets a test.
 * <p>
 * It reads the COMPILED method rather than the source, so a call that was commented out, moved into
 * a branch that is never taken from there, or left behind in a stale javadoc cannot satisfy it: the
 * two {@code invokestatic} instructions have to be in {@code execute()}'s bytecode, in that order.
 * The class file is read as a resource ({@link Class#getResourceAsStream}), the way
 * {@link BareErrorStringRatchetTest} reads constant pools; JaCoCo instruments classes as they are
 * LOADED and never rewrites the file, so what is parsed here is javac's own output.
 * <p>
 * Both spellings of the pre-flight are accepted - the extracted {@code preflightRefusal} seam and a
 * direct {@code storedRemoteRefusal} call - because the contract being pinned is the ORDER, not the
 * shape of the refactor. What may never happen is that neither is there, or that consent comes
 * first.
 */
public class GitToolPreflightOrderRatchetTest
{
    /** The method whose bytecode carries the contract. */
    private static final String EXECUTE = "execute"; //$NON-NLS-1$

    /** The extracted read-only gauntlet, and the older inline call it replaced. */
    private static final List<String> PRE_FLIGHT_CALLS =
        List.of("preflightRefusal", "storedRemoteRefusal"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The gate that may block on a human: it has to come last. */
    private static final String CONSENT_CALL = "requireConsentFor"; //$NON-NLS-1$

    /** Only calls into GitTool itself count - a same-named method elsewhere is not the contract. */
    private static final String OWNER = "GitTool"; //$NON-NLS-1$

    private static final String CODE_ATTRIBUTE = "Code"; //$NON-NLS-1$

    @Test
    public void executeRunsTheReadOnlyPreFlightBeforeTheConsentGate() throws IOException
    {
        ClassFile gitTool = ClassFile.read(GitTool.class, EXECUTE);
        List<byte[]> bodies = gitTool.bodies();
        // Positive control: a parse that found no code at all would make this ratchet's failure mode
        // identical to its pass. execute() exists and is not empty - if that ever stops being true,
        // say so instead of reporting an order that was never read.
        assertTrue("no bytecode was read for GitTool.execute(): the class-file parse is broken, and " //$NON-NLS-1$
            + "an order ratchet that reads nothing proves nothing", //$NON-NLS-1$
            !bodies.isEmpty() && bodies.get(0).length > 0);

        int preFlightAt = -1;
        int consentAt = -1;
        for (byte[] body : bodies)
        {
            List<StaticCall> calls = gitTool.staticCallsIn(body);
            int preFlight = firstOffsetOf(calls, PRE_FLIGHT_CALLS);
            int consent = firstOffsetOf(calls, List.of(CONSENT_CALL));
            // A bridge or a synthetic sibling carries neither call; the real execute() carries both.
            if (preFlight >= 0 || consent >= 0)
            {
                preFlightAt = preFlight;
                consentAt = consent;
            }
        }

        assertTrue("GitTool.execute() no longer calls " + PRE_FLIGHT_CALLS + ": the read-only " //$NON-NLS-1$
            + "pre-flight is dead code, so an operand outside the work tree and a stored remote " //$NON-NLS-1$
            + "whose credential cannot be masked both reach git again (issue #314)", preFlightAt >= 0); //$NON-NLS-1$
        assertTrue("GitTool.execute() no longer calls " + CONSENT_CALL + ": a write-capable git " //$NON-NLS-1$
            + "subcommand would run without the destructive-consent gate", consentAt >= 0); //$NON-NLS-1$
        assertTrue("GitTool.execute() asks for consent at bytecode offset " + consentAt //$NON-NLS-1$
            + " BEFORE the read-only pre-flight at " + preFlightAt + ". Consent must stay LAST: a " //$NON-NLS-1$
            + "command this tool refuses anyway must fail on its own error instead of sitting in " //$NON-NLS-1$
            + "front of a human (or burning the consent timeout) for a call that could never run.", //$NON-NLS-1$
            preFlightAt < consentAt);
    }

    /**
     * The offset of the first call to any of {@code names}, or {@code -1} when none is there.
     *
     * @param calls every static call in one method body, in bytecode order
     * @param names the callee names to look for
     * @return the lowest matching bytecode offset, or {@code -1}
     */
    private static int firstOffsetOf(List<StaticCall> calls, List<String> names)
    {
        for (StaticCall call : calls)
        {
            if (names.contains(call.method))
            {
                return call.offset;
            }
        }
        return -1;
    }

    /** One {@code invokestatic} into {@link #OWNER}: where it sits, and what it calls. */
    private static final class StaticCall
    {
        private final int offset;

        private final String method;

        private StaticCall(int offset, String method)
        {
            this.offset = offset;
            this.method = method;
        }
    }

    /**
     * The pieces of a compiled class this ratchet needs: the constant pool (to name a call's target)
     * and the bytecode of a chosen method.
     */
    private static final class ClassFile
    {
        /** Text of every CONSTANT_Utf8 entry, by pool index. */
        private final String[] utf8;

        /** For a CONSTANT_Class: the pool index of its name. */
        private final int[] classNames;

        /** For a CONSTANT_Methodref: the pool index of its owning CONSTANT_Class. */
        private final int[] refOwners;

        /** For a CONSTANT_Methodref: the pool index of its CONSTANT_NameAndType. */
        private final int[] refNameAndTypes;

        /** For a CONSTANT_NameAndType: the pool index of its NAME. */
        private final int[] nameAndTypeNames;

        /** Bytecode of every method carrying the requested name, in declaration order. */
        private final List<byte[]> bodies = new ArrayList<>();

        /** The only method whose bytecode is kept; everything else is skipped. */
        private final String wantedMethod;

        private ClassFile(int poolSize, String wantedMethod)
        {
            utf8 = new String[poolSize];
            classNames = new int[poolSize];
            refOwners = new int[poolSize];
            refNameAndTypes = new int[poolSize];
            nameAndTypeNames = new int[poolSize];
            this.wantedMethod = wantedMethod;
        }

        /**
         * Parses {@code clazz}'s compiled form off the classpath, keeping the bytecode of
         * {@code method}.
         *
         * @param clazz the class to read
         * @param method the method whose bytecode is wanted
         * @return the parsed class file
         * @throws IOException when the resource cannot be read or is not a class file
         */
        private static ClassFile read(Class<?> clazz, String method) throws IOException
        {
            String resource = clazz.getSimpleName() + ".class"; //$NON-NLS-1$
            try (InputStream raw = clazz.getResourceAsStream(resource))
            {
                if (raw == null)
                {
                    fail("class resource not found for " + clazz.getName() + " (expected " + resource //$NON-NLS-1$ //$NON-NLS-2$
                        + " next to the class) - an order ratchet must never pass because it read " //$NON-NLS-1$
                        + "nothing"); //$NON-NLS-1$
                }
                try (DataInputStream in = new DataInputStream(raw))
                {
                    return parse(in, method);
                }
            }
        }

        /** The bodies of every method the parse was asked for. */
        private List<byte[]> bodies()
        {
            return bodies;
        }

        /**
         * Every {@code invokestatic} into {@link GitToolPreflightOrderRatchetTest#OWNER} in one
         * method body, in bytecode order. The body is walked instruction by instruction (including
         * the variable-length {@code wide} / {@code tableswitch} / {@code lookupswitch} forms), so a
         * constant-pool index that happens to look like an opcode inside another instruction's
         * operands cannot be mistaken for a call.
         *
         * @param code the method's bytecode
         * @return the calls, in the order they are executed
         */
        private List<StaticCall> staticCallsIn(byte[] code)
        {
            List<StaticCall> calls = new ArrayList<>();
            int pc = 0;
            while (pc < code.length)
            {
                int opcode = code[pc] & 0xFF;
                if (opcode == 0xB8) // invokestatic
                {
                    int ref = readUnsignedShort(code, pc + 1);
                    String owner = simpleName(nameOf(classNames, refOwners[ref]));
                    if (OWNER.equals(owner))
                    {
                        int nameAndType = refNameAndTypes[ref];
                        calls.add(new StaticCall(pc, text(nameAndTypeNames[nameAndType])));
                    }
                }
                pc += instructionLength(code, pc);
            }
            return calls;
        }

        /** The name a CONSTANT_Class entry points at, or an empty string. */
        private String nameOf(int[] indirection, int poolIndex)
        {
            if (poolIndex <= 0 || poolIndex >= indirection.length)
            {
                return ""; //$NON-NLS-1$
            }
            return text(indirection[poolIndex]);
        }

        private String text(int poolIndex)
        {
            if (poolIndex <= 0 || poolIndex >= utf8.length || utf8[poolIndex] == null)
            {
                return ""; //$NON-NLS-1$
            }
            return utf8[poolIndex];
        }

        private static ClassFile parse(DataInputStream in, String method) throws IOException
        {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE)
            {
                throw new IOException("not a class file (bad magic)"); //$NON-NLS-1$
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            ClassFile parsed = new ClassFile(in.readUnsignedShort(), method);
            parsed.readConstantPool(in);
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this class
            in.readUnsignedShort(); // super class
            skipFully(in, in.readUnsignedShort() * 2); // interfaces
            parsed.skipMembers(in); // fields
            parsed.readMethods(in);
            return parsed;
        }

        private void readConstantPool(DataInputStream in) throws IOException
        {
            for (int i = 1; i < utf8.length; i++)
            {
                int tag = in.readUnsignedByte();
                switch (tag)
                {
                    case 1: // CONSTANT_Utf8
                        utf8[i] = in.readUTF();
                        break;
                    case 7: // CONSTANT_Class
                        classNames[i] = in.readUnsignedShort();
                        break;
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        in.readUnsignedShort();
                        break;
                    case 15: // CONSTANT_MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                        refOwners[i] = in.readUnsignedShort();
                        refNameAndTypes[i] = in.readUnsignedShort();
                        break;
                    case 12: // CONSTANT_NameAndType
                        nameAndTypeNames[i] = in.readUnsignedShort();
                        in.readUnsignedShort(); // descriptor
                        break;
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
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
        }

        /** Skips a whole fields (or methods) table. */
        private void skipMembers(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++)
            {
                in.readUnsignedShort(); // access flags
                in.readUnsignedShort(); // name
                in.readUnsignedShort(); // descriptor
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++)
                {
                    in.readUnsignedShort(); // attribute name
                    skipFully(in, in.readInt());
                }
            }
        }

        /** Reads the methods table, keeping the bytecode of every method named {@link #wantedMethod}. */
        private void readMethods(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++)
            {
                in.readUnsignedShort(); // access flags
                String name = text(in.readUnsignedShort());
                in.readUnsignedShort(); // descriptor
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++)
                {
                    String attribute = text(in.readUnsignedShort());
                    int length = in.readInt();
                    if (!wantedMethod.equals(name) || !CODE_ATTRIBUTE.equals(attribute))
                    {
                        skipFully(in, length);
                        continue;
                    }
                    in.readUnsignedShort(); // max stack
                    in.readUnsignedShort(); // max locals
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    bodies.add(code);
                    // The exception table and the Code attribute's own attributes follow.
                    skipFully(in, length - 8 - codeLength);
                }
            }
        }
    }

    /** The class name without its package, from the internal {@code a/b/C} form. */
    private static String simpleName(String internalName)
    {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash < 0 ? internalName : internalName.substring(lastSlash + 1);
    }

    private static int readUnsignedShort(byte[] code, int at)
    {
        return ((code[at] & 0xFF) << 8) | (code[at + 1] & 0xFF);
    }

    private static int readInt(byte[] code, int at)
    {
        return ((code[at] & 0xFF) << 24) | ((code[at + 1] & 0xFF) << 16) | ((code[at + 2] & 0xFF) << 8)
            | (code[at + 3] & 0xFF);
    }

    /**
     * The full length of the instruction at {@code pc}, including its operands.
     *
     * @param code the method's bytecode
     * @param pc the instruction's offset
     * @return the number of bytes it occupies
     */
    private static int instructionLength(byte[] code, int pc)
    {
        int opcode = code[pc] & 0xFF;
        if (opcode == 0xC4) // wide
        {
            return (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4; // wide iinc, else wide load/store/ret
        }
        if (opcode == 0xAA) // tableswitch: padding, default, low, high, then one offset per case
        {
            int operands = padded(pc);
            int low = readInt(code, operands + 4);
            int high = readInt(code, operands + 8);
            return operands + 12 + (high - low + 1) * 4 - pc;
        }
        if (opcode == 0xAB) // lookupswitch: padding, default, npairs, then match/offset pairs
        {
            int operands = padded(pc);
            return operands + 8 + readInt(code, operands + 4) * 8 - pc;
        }
        int length = LENGTHS[opcode];
        if (length <= 0)
        {
            throw new IllegalStateException("unknown opcode 0x" + Integer.toHexString(opcode) //$NON-NLS-1$
                + " at " + pc); //$NON-NLS-1$
        }
        return length;
    }

    /** The offset of a switch instruction's operands: the next 4-byte boundary after the opcode. */
    private static int padded(int pc)
    {
        return (pc + 4) / 4 * 4;
    }

    /** Instruction lengths by opcode; the three variable-length forms are handled separately. */
    private static final int[] LENGTHS = buildLengths();

    private static int[] buildLengths()
    {
        int[] lengths = new int[256];
        Arrays.fill(lengths, 1); // most instructions are a bare opcode
        // One operand byte: the small pushes, the single-index loads/stores, ret, newarray.
        for (int opcode : new int[] { 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38,
            0x39, 0x3A, 0xA9, 0xBC })
        {
            lengths[opcode] = 2;
        }
        // Two operand bytes: sipush, the wide ldc forms, iinc, the field/method refs, the type ops.
        for (int opcode : new int[] { 0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7,
            0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 })
        {
            lengths[opcode] = 3;
        }
        for (int opcode = 0x99; opcode <= 0xA8; opcode++) // ifeq..jsr: 16-bit branch offsets
        {
            lengths[opcode] = 3;
        }
        lengths[0xC5] = 4; // multianewarray
        lengths[0xB9] = 5; // invokeinterface
        lengths[0xBA] = 5; // invokedynamic
        lengths[0xC8] = 5; // goto_w
        lengths[0xC9] = 5; // jsr_w
        lengths[0xAA] = -1; // tableswitch
        lengths[0xAB] = -1; // lookupswitch
        lengths[0xC4] = -1; // wide
        for (int opcode = 0xCB; opcode < 0x100; opcode++) // reserved / not emitted by javac
        {
            lengths[opcode] = -1;
        }
        return lengths;
    }

    private static void skipFully(DataInputStream in, int bytes) throws IOException
    {
        int remaining = bytes;
        while (remaining > 0)
        {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0)
            {
                throw new IOException("truncated class file: " + remaining + " bytes missing"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            remaining -= skipped;
        }
    }
}
