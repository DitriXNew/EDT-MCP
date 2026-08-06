/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;

/**
 * Ratchet for issue #331: in {@code delete_metadata} the destructive-consent gate must be a SINGLE
 * point that every mutating branch goes through, and no branch may reach a write without it.
 * <p>
 * Why a ratchet and not a unit test: driving the real dispatch needs a resolved EDT project plus BM
 * services, which a headless run has none of, and the gate itself may ASK a human. Every behavioural
 * case therefore drives the authorization step directly ({@code DeleteMetadataToolTest}), and that
 * test says so in as many words - which leaves exactly the thing #331 is about, the WIRING,
 * unpinned. The issue asked for a single point "so the next new branch cannot forget it again"; a
 * single point nobody checks is forgotten the same way. This reads the compiled class instead.
 * <p>
 * The model is deliberately small. A mutation runs when the method holding it runs, so the question
 * is which methods {@code executeOnUiThread} can reach. Two kinds of edge lead somewhere:
 * <ul>
 * <li>an ordinary call ({@code invokevirtual/special/static/interface}) into this class;</li>
 * <li>a lambda / method reference, resolved through the {@code BootstrapMethods} attribute to the
 * method that actually holds the body - followed exactly like a call, EXCEPT when the callback's
 * type is {@code DeleteWrite}: that one runs only when something invokes it, and the third test
 * below proves the only invoker is {@code deleteWithConsent}, after an ALLOW.</li>
 * </ul>
 * Following ordinary lambdas matters: hiding the write in a {@code Supplier} the branch calls itself
 * would otherwise satisfy a walk that ignored {@code invokedynamic} altogether.
 * <p>
 * What it does NOT prove, stated plainly:
 * <ul>
 * <li>the mutation entry points are a LIST ({@link #MUTATIONS}). A branch that writes through some
 * other API - a new writer helper, raw EMF outside a transaction - is invisible here, and is covered
 * only by the transaction-boundary rules in CLAUDE.md. The list carries a positive control, so it
 * fails loudly when a name drifts instead of silently checking nothing;</li>
 * <li>the two ORDER assertions compare bytecode offsets. That catches the shape both defects
 * actually had (the later step written above the earlier one); it is not a dominance proof, so a
 * branch that jumps over the earlier call could still pass. For {@code deleteWithConsent} the
 * semantics are pinned behaviourally as well ({@code testConsentRejectNeverRunsTheWrite}).</li>
 * </ul>
 * Where it errs, it errs CLOSED, which is the only safe direction for a gate: creating a non-gated
 * callback counts as running it, so wrapping the write in one callback and handing THAT to the gate
 * ({@code Supplier<String> w = () -> write(); deleteWithConsent(preview, w::get);}) is reported even
 * though it would in fact be safe. Modelling callback consumption to allow it would buy nothing - a
 * branch's write goes to the gate directly.
 * The compiled class is read as a resource ({@link Class#getResourceAsStream}), the way
 * {@code BareErrorStringRatchetTest} reads constant pools: a call that was commented out, or left
 * behind in a javadoc, cannot satisfy it. JaCoCo instruments classes as they are LOADED and never
 * rewrites the file, so what is parsed here is the compiler's own output. Nothing depends on how a
 * lambda body is NAMED (Tycho's compiler emits {@code lambda$0}, javac {@code lambda$new$0}) - the
 * link comes from {@code BootstrapMethods}, not from the name.
 */
public class DeleteMetadataConsentSinglePointRatchetTest
{
    /** The tool's dispatch entry point: every branch is reached from here. */
    private static final String ENTRY = "executeOnUiThread"; //$NON-NLS-1$

    /** The single authorization point. */
    private static final String GATE = "deleteWithConsent"; //$NON-NLS-1$

    /** The tool's own class, as the constant pool spells its owner. */
    private static final String SELF = "DeleteMetadataTool"; //$NON-NLS-1$

    /** The callback type a branch hands to the gate. */
    private static final String WRITE_CALLBACK_TYPE = "DeleteMetadataTool$DeleteWrite"; //$NON-NLS-1$

    /** Its only invocation must sit inside the gate. */
    private static final String WRITE_CALLBACK = WRITE_CALLBACK_TYPE + "#perform"; //$NON-NLS-1$

    /** The consent seam the gate asks through. */
    private static final String ASK = "DeleteMetadataTool$ConsentRequester#request"; //$NON-NLS-1$

    /** The gate singleton: only the production constructor's lambda may touch it. */
    private static final String SINGLETON = "DestructiveConsentGate#getInstance"; //$NON-NLS-1$

    /** ... and only there may consent actually be requested from it. */
    private static final String REQUIRE_CONSENT = "DestructiveConsentGate#requireConsent"; //$NON-NLS-1$

    /**
     * Every call through which this tool changes something: the md-refactoring, the two form writers,
     * the BM write transaction, the on-disk export and the physical removal of a form's folder. None
     * of them may be reachable without the gate.
     */
    private static final List<String> MUTATIONS = List.of(
        "IRefactoring#perform", //$NON-NLS-1$
        "FormElementWriter#writeEditableForm", //$NON-NLS-1$
        "FormElementWriter#writeMdForm", //$NON-NLS-1$
        "BmTransactions#write", //$NON-NLS-1$
        "BmTransactions#forceExportToDisk", //$NON-NLS-1$
        "IFolder#delete"); //$NON-NLS-1$

    /** The XDTO branch's target lookup: it has to run BEFORE the gate, not after it. */
    private static final String XDTO_BRANCH = "performXdtoMemberDelete"; //$NON-NLS-1$

    private static final String XDTO_LOOKUP = "DeleteMetadataTool#locateXdtoMemberInModel"; //$NON-NLS-1$

    @Test
    public void theGateSingletonIsConsultedInExactlyOnePlace()
    {
        ClassFile tool = read();
        Set<String> asks = tool.methodsCalling(SINGLETON);
        Set<String> requires = tool.methodsCalling(REQUIRE_CONSENT);

        assertEquals("the destructive-consent singleton must be reached from exactly one method of " //$NON-NLS-1$
            + SELF + " - the production constructor's requester lambda. Found: " + asks //$NON-NLS-1$
            + ". A branch with its own gate call is a branch that can drift: its own preview, its own " //$NON-NLS-1$
            + "denial message, its own ordering relative to validation - and one that cannot be driven " //$NON-NLS-1$
            + "by the ConsentRequester test seam. Route it through " + GATE + " instead (issue #331).", //$NON-NLS-1$ //$NON-NLS-2$
            1, asks.size());
        assertEquals("consent must be REQUESTED only where the singleton is obtained", asks, requires); //$NON-NLS-1$

        String gate = tool.requireSingleDeclaration(GATE);
        assertTrue(GATE + " must ask through the ConsentRequester seam, never the singleton - " //$NON-NLS-1$
            + "otherwise no unit test can drive a refusal", //$NON-NLS-1$
            !asks.contains(gate) && tool.firstOffsetOf(gate, ASK) >= 0);
    }

    @Test
    public void noBranchCanReachAWriteWithoutPassingThroughTheGate()
    {
        ClassFile tool = read();

        // Positive control: a mutation name that no longer occurs would make this test's failure mode
        // identical to its pass - it would guard nothing and stay green forever.
        for (String mutation : MUTATIONS)
        {
            assertTrue("the mutation entry point '" + mutation + "' no longer occurs in " + SELF //$NON-NLS-1$ //$NON-NLS-2$
                + ": this ratchet would be checking a name that is not there. Update " //$NON-NLS-1$
                + "MUTATIONS to the call the branch really writes through.", //$NON-NLS-1$
                !tool.methodsCalling(mutation).isEmpty());
        }
        // ... and the same for the mechanism itself: if no callback of the gate's type were found, the
        // walk below would be a plain call graph and would pass for the wrong reason.
        assertTrue("no lambda of type " + WRITE_CALLBACK_TYPE + " was found in " + SELF //$NON-NLS-1$ //$NON-NLS-2$
            + ": either BootstrapMethods parsing is broken or no branch hands its write to the gate", //$NON-NLS-1$
            tool.hasCallbackOfType(WRITE_CALLBACK_TYPE));

        Set<String> ungated = tool.reachableWithout(tool.requireSingleDeclaration(ENTRY), WRITE_CALLBACK_TYPE);
        assertTrue("nothing was reached from " + ENTRY + "(): the call-graph walk is broken, and a " //$NON-NLS-1$ //$NON-NLS-2$
            + "reachability ratchet that reaches nothing proves nothing", ungated.size() > 1); //$NON-NLS-1$
        assertTrue(ENTRY + "() must still reach the authorization point", //$NON-NLS-1$
            ungated.contains(tool.requireSingleDeclaration(GATE)));

        Map<String, Set<String>> escapes = new LinkedHashMap<>();
        for (String method : ungated)
        {
            for (Call call : tool.callsIn(method))
            {
                if (MUTATIONS.contains(call.target()))
                {
                    escapes.computeIfAbsent(method, unused -> new TreeSet<>()).add(call.target());
                }
            }
            // A method REFERENCE to a mutation ({@code refactoring::perform} handed to a Runnable the
            // branch then runs) never appears as a call, and its target is not one of our methods, so
            // the walk cannot step into it. It still names a mutation - count it as one.
            for (Callback callback : tool.callbacksIn(method))
            {
                if (!WRITE_CALLBACK_TYPE.equals(callback.type) && MUTATIONS.contains(callback.target()))
                {
                    escapes.computeIfAbsent(method, unused -> new TreeSet<>()).add(callback.target());
                }
            }
        }
        assertTrue("these methods are reachable from " + ENTRY + "() without passing through the " //$NON-NLS-1$ //$NON-NLS-2$
            + "gate's callback AND perform a mutation, so the write happens whether or not consent " //$NON-NLS-1$
            + "was granted: " + escapes + ". Every delete branch must hand its write to " + GATE //$NON-NLS-1$ //$NON-NLS-2$
            + " as a DeleteWrite callback (issue #331) - see how deleteFormObject does it.", //$NON-NLS-1$
            escapes.isEmpty());
    }

    @Test
    public void theWriteCallbackIsInvokedOnlyByTheGateAndOnlyAfterAsking()
    {
        ClassFile tool = read();
        String gate = tool.requireSingleDeclaration(GATE);
        Set<String> invokers = tool.methodsCalling(WRITE_CALLBACK);

        assertEquals("a DeleteWrite callback must be invoked ONLY by " + GATE + ": that single " //$NON-NLS-1$ //$NON-NLS-2$
            + "invocation is what makes 'reachable only through the callback' mean 'reachable only " //$NON-NLS-1$
            + "after ALLOW'. Found: " + invokers, Set.of(gate), invokers); //$NON-NLS-1$

        int asked = tool.firstOffsetOf(gate, ASK);
        int wrote = tool.firstOffsetOf(gate, WRITE_CALLBACK);
        assertTrue(GATE + " no longer asks for consent", asked >= 0); //$NON-NLS-1$
        assertTrue(GATE + " invokes the write at bytecode offset " + wrote + ", BEFORE it asks at " //$NON-NLS-1$ //$NON-NLS-2$
            + asked + ". The mutation must run only after an ALLOW.", asked < wrote); //$NON-NLS-1$
    }

    @Test
    public void theXdtoBranchResolvesItsTargetBeforeItAsks()
    {
        // The ordering defect #331 recorded: this branch asked first and looked the member up only
        // inside the write transaction, so a typo in the member name raised a destructive prompt at a
        // human and answered "not found" only after it had been dealt with.
        ClassFile tool = read();
        String branch = tool.requireSingleDeclaration(XDTO_BRANCH);
        int lookup = tool.firstOffsetOf(branch, XDTO_LOOKUP);
        int gate = tool.firstOffsetOf(branch, SELF + '#' + GATE);

        assertTrue(XDTO_BRANCH + "() no longer looks its target up before writing: a typo would " //$NON-NLS-1$
            + "reach the gate", lookup >= 0); //$NON-NLS-1$
        assertTrue(XDTO_BRANCH + "() no longer goes through " + GATE, gate >= 0); //$NON-NLS-1$
        assertTrue(XDTO_BRANCH + "() asks for consent at bytecode offset " + gate + " BEFORE it " //$NON-NLS-1$ //$NON-NLS-2$
            + "resolves the target at " + lookup + ". Resolve first: a delete that can only answer " //$NON-NLS-1$
            + "'not found' must never raise a destructive prompt (issue #331).", lookup < gate); //$NON-NLS-1$
    }

    private static ClassFile read()
    {
        try
        {
            return ClassFile.read(DeleteMetadataTool.class);
        }
        catch (IOException e)
        {
            fail("could not read the compiled " + SELF + ": " + e //$NON-NLS-1$ //$NON-NLS-2$
                + " - a wiring ratchet must never pass because it read nothing"); //$NON-NLS-1$
            throw new IllegalStateException(e);
        }
    }

    /** One invocation instruction: where it sits and what it calls, as {@code Owner#method}. */
    private static final class Call
    {
        private final int offset;

        private final String owner;

        private final String name;

        private final String descriptor;

        Call(int offset, String owner, String name, String descriptor)
        {
            this.offset = offset;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        String target()
        {
            return owner + '#' + name;
        }

        /** The callee's node key in this class's graph: overloads are DIFFERENT methods. */
        String node()
        {
            return name + descriptor;
        }
    }

    /**
     * One lambda / method reference created by an {@code invokedynamic}: the functional interface it
     * produces, and the method that actually holds the body (from {@code BootstrapMethods}).
     */
    private static final class Callback
    {
        private final String type;

        private final String implOwner;

        private final String implMethod;

        private final String implDescriptor;

        Callback(String type, String implOwner, String implMethod, String implDescriptor)
        {
            this.type = type;
            this.implOwner = implOwner;
            this.implMethod = implMethod;
            this.implDescriptor = implDescriptor;
        }

        /** The body's owner and name, as {@code Owner#method} - comparable with {@link Call#target()}. */
        String target()
        {
            return implOwner + '#' + implMethod;
        }

        /** The body's node key in this class's graph. */
        String node()
        {
            return implMethod + implDescriptor;
        }
    }

    /**
     * The pieces of a compiled class this ratchet needs: the constant pool, the
     * {@code BootstrapMethods} table, and for every method its ordinary calls plus the callbacks it
     * creates.
     */
    private static final class ClassFile
    {
        private final String[] utf8;

        private final int[] classNames;

        private final int[] refOwners;

        private final int[] refNameAndTypes;

        private final int[] nameAndTypeNames;

        private final int[] nameAndTypeDescriptors;

        /** For a CONSTANT_MethodHandle: the pool index of the ref it points at. */
        private final int[] methodHandleRefs;

        /** For a CONSTANT_InvokeDynamic: its BootstrapMethods index and its NameAndType. */
        private final int[] indyBootstraps;

        private final int[] indyNameAndTypes;

        /** BootstrapMethods: the bootstrap MethodHandle and the static arguments, per entry. */
        private int[] bootstrapHandles = new int[0];

        private int[][] bootstrapArguments = new int[0][];

        /** Raw bodies, kept until BootstrapMethods (a CLASS attribute) has been read. */
        private final Map<String, List<byte[]>> bodies = new LinkedHashMap<>();

        /** How many methods carry each name - an ordering check may not straddle two overloads. */
        private final Map<String, Integer> declarations = new LinkedHashMap<>();

        /** Node keys ({@code name+descriptor}) by bare name, so an assertion can name a method. */
        private final Map<String, Set<String>> nodesByName = new LinkedHashMap<>();

        private final Map<String, List<Call>> calls = new LinkedHashMap<>();

        private final Map<String, List<Callback>> callbacks = new LinkedHashMap<>();

        private ClassFile(int poolSize)
        {
            utf8 = new String[poolSize];
            classNames = new int[poolSize];
            refOwners = new int[poolSize];
            refNameAndTypes = new int[poolSize];
            nameAndTypeNames = new int[poolSize];
            nameAndTypeDescriptors = new int[poolSize];
            methodHandleRefs = new int[poolSize];
            indyBootstraps = new int[poolSize];
            indyNameAndTypes = new int[poolSize];
        }

        /** Every method that contains a call to {@code target} ({@code Owner#method}). */
        Set<String> methodsCalling(String target)
        {
            Set<String> found = new TreeSet<>();
            for (Map.Entry<String, List<Call>> method : calls.entrySet())
            {
                for (Call call : method.getValue())
                {
                    if (target.equals(call.target()))
                    {
                        found.add(method.getKey());
                    }
                }
            }
            return found;
        }

        List<Call> callsIn(String node)
        {
            return calls.getOrDefault(node, List.of());
        }

        List<Callback> callbacksIn(String node)
        {
            return callbacks.getOrDefault(node, List.of());
        }

        /** Whether any method creates a callback of {@code type} - the walk's own positive control. */
        boolean hasCallbackOfType(String type)
        {
            for (List<Callback> created : callbacks.values())
            {
                for (Callback callback : created)
                {
                    if (type.equals(callback.type))
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The node key of {@code method}, failing when it is overloaded: an ordering assertion names a
         * method, and across two same-named methods an offset comparison would read unrelated
         * instructions.
         *
         * @param method the bare method name an ordering assertion is about to read
         * @return its single node key
         */
        String requireSingleDeclaration(String method)
        {
            int declared = declarations.getOrDefault(method, 0);
            assertEquals("an ordering assertion names " + method + "(), so it must be declared " //$NON-NLS-1$ //$NON-NLS-2$
                + "exactly once; split the assertion per descriptor before overloading it", //$NON-NLS-1$
                1, declared);
            return nodesByName.get(method).iterator().next();
        }

        /** The bytecode offset of the first call to {@code target} inside {@code node}, or -1. */
        int firstOffsetOf(String node, String target)
        {
            int first = -1;
            for (Call call : callsIn(node))
            {
                if (target.equals(call.target()) && (first < 0 || call.offset < first))
                {
                    first = call.offset;
                }
            }
            return first;
        }

        /**
         * The methods of this class reachable from {@code entry} by ordinary calls AND by every
         * callback EXCEPT those of {@code gatedType}: a body handed over as {@code gatedType} runs
         * only when that interface is invoked, and who may invoke it is a separate assertion.
         *
         * @param entry the method to start from
         * @param gatedType the callback type whose bodies are NOT followed
         * @return the reachable method names, {@code entry} included
         */
        Set<String> reachableWithout(String entry, String gatedType)
        {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            seen.add(entry);
            queue.add(entry);
            while (!queue.isEmpty())
            {
                String from = queue.poll();
                for (Call call : callsIn(from))
                {
                    if (SELF.equals(call.owner) && bodies.containsKey(call.node()) && seen.add(call.node()))
                    {
                        queue.add(call.node());
                    }
                }
                for (Callback callback : callbacksIn(from))
                {
                    if (!gatedType.equals(callback.type) && SELF.equals(callback.implOwner)
                        && bodies.containsKey(callback.node()) && seen.add(callback.node()))
                    {
                        queue.add(callback.node());
                    }
                }
            }
            return seen;
        }

        private static ClassFile read(Class<?> clazz) throws IOException
        {
            String resource = clazz.getSimpleName() + ".class"; //$NON-NLS-1$
            try (InputStream raw = clazz.getResourceAsStream(resource))
            {
                if (raw == null)
                {
                    throw new IOException("class resource not found: " + resource); //$NON-NLS-1$
                }
                try (DataInputStream in = new DataInputStream(raw))
                {
                    return parse(in);
                }
            }
        }

        private static ClassFile parse(DataInputStream in) throws IOException
        {
            if (in.readInt() != 0xCAFEBABE)
            {
                throw new IOException("not a class file (bad magic)"); //$NON-NLS-1$
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            ClassFile parsed = new ClassFile(in.readUnsignedShort());
            parsed.readConstantPool(in);
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this class
            in.readUnsignedShort(); // super class
            skipFully(in, in.readUnsignedShort() * 2); // interfaces
            parsed.skipMembers(in); // fields
            parsed.readMethods(in);
            parsed.readClassAttributes(in); // BootstrapMethods lives here, AFTER the methods
            parsed.resolveBodies();
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
                        in.readUnsignedByte(); // reference kind
                        methodHandleRefs[i] = in.readUnsignedShort();
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                        refOwners[i] = in.readUnsignedShort();
                        refNameAndTypes[i] = in.readUnsignedShort();
                        break;
                    case 12: // CONSTANT_NameAndType
                        nameAndTypeNames[i] = in.readUnsignedShort();
                        nameAndTypeDescriptors[i] = in.readUnsignedShort();
                        break;
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        indyBootstraps[i] = in.readUnsignedShort();
                        indyNameAndTypes[i] = in.readUnsignedShort();
                        break;
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
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

        /** Skips a whole fields table. */
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

        private void readMethods(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++)
            {
                in.readUnsignedShort(); // access flags
                String name = text(in.readUnsignedShort());
                String descriptor = text(in.readUnsignedShort());
                int attributes = in.readUnsignedShort();
                // Keyed by name AND descriptor: two overloads are two methods, and merging them would
                // import one's mutations into the other's reachability (issue #331 review).
                String node = name + descriptor;
                declarations.merge(name, 1, Integer::sum);
                nodesByName.computeIfAbsent(name, unused -> new TreeSet<>()).add(node);
                List<byte[]> body = bodies.computeIfAbsent(node, unused -> new ArrayList<>());
                for (int a = 0; a < attributes; a++)
                {
                    String attribute = text(in.readUnsignedShort());
                    int length = in.readInt();
                    if (!"Code".equals(attribute)) //$NON-NLS-1$
                    {
                        skipFully(in, length);
                        continue;
                    }
                    in.readUnsignedShort(); // max stack
                    in.readUnsignedShort(); // max locals
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    body.add(code);
                    // The exception table and the Code attribute's own attributes follow.
                    skipFully(in, length - 8 - codeLength);
                }
            }
        }

        /** Reads the class-level attributes, keeping {@code BootstrapMethods}. */
        private void readClassAttributes(DataInputStream in) throws IOException
        {
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++)
            {
                String attribute = text(in.readUnsignedShort());
                int length = in.readInt();
                if (!"BootstrapMethods".equals(attribute)) //$NON-NLS-1$
                {
                    skipFully(in, length);
                    continue;
                }
                int count = in.readUnsignedShort();
                bootstrapHandles = new int[count];
                bootstrapArguments = new int[count][];
                for (int b = 0; b < count; b++)
                {
                    bootstrapHandles[b] = in.readUnsignedShort();
                    int arguments = in.readUnsignedShort();
                    bootstrapArguments[b] = new int[arguments];
                    for (int g = 0; g < arguments; g++)
                    {
                        bootstrapArguments[b][g] = in.readUnsignedShort();
                    }
                }
            }
        }

        /** Walks every kept body once the constant pool AND BootstrapMethods are both available. */
        private void resolveBodies()
        {
            for (Map.Entry<String, List<byte[]>> method : bodies.entrySet())
            {
                List<Call> found = calls.computeIfAbsent(method.getKey(), unused -> new ArrayList<>());
                List<Callback> made = callbacks.computeIfAbsent(method.getKey(), unused -> new ArrayList<>());
                for (byte[] code : method.getValue())
                {
                    walk(code, found, made);
                }
            }
        }

        /**
         * Walks one method body instruction by instruction (including the variable-length {@code wide}
         * / {@code tableswitch} / {@code lookupswitch} forms), so a constant-pool index that happens to
         * look like an opcode inside another instruction's operands cannot be mistaken for a call.
         *
         * @param code the method's bytecode
         * @param found collects its ordinary calls, in execution order
         * @param made collects the callbacks it creates
         */
        private void walk(byte[] code, List<Call> found, List<Callback> made)
        {
            int pc = 0;
            while (pc < code.length)
            {
                int opcode = code[pc] & 0xFF;
                if (opcode >= 0xB6 && opcode <= 0xB9) // invokevirtual / special / static / interface
                {
                    int ref = readUnsignedShort(code, pc + 1);
                    int nameAndType = refNameAndTypes[ref];
                    found.add(new Call(pc, simpleName(nameOf(classNames, refOwners[ref])),
                        text(nameAndTypeNames[nameAndType]), text(nameAndTypeDescriptors[nameAndType])));
                }
                else if (opcode == 0xBA) // invokedynamic: creates a callback, does not run it
                {
                    Callback callback = callbackAt(readUnsignedShort(code, pc + 1));
                    if (callback != null)
                    {
                        made.add(callback);
                    }
                }
                pc += instructionLength(code, pc);
            }
        }

        /**
         * The lambda / method reference an {@code invokedynamic} constant creates, or {@code null}
         * when it is not one (string concatenation compiles to an {@code invokedynamic} too, through
         * {@code StringConcatFactory}, and carries no implementation handle).
         *
         * @param poolIndex the CONSTANT_InvokeDynamic index
         * @return the callback, or {@code null}
         */
        private Callback callbackAt(int poolIndex)
        {
            int bootstrap = indyBootstraps[poolIndex];
            if (bootstrap >= bootstrapHandles.length)
            {
                return null;
            }
            String factory = simpleName(nameOf(classNames, refOwners[methodHandleRefs[bootstrapHandles[bootstrap]]]));
            if (!"LambdaMetafactory".equals(factory)) //$NON-NLS-1$
            {
                return null;
            }
            int[] arguments = bootstrapArguments[bootstrap];
            // metafactory(caller, name, type, samMethodType, IMPL, instantiatedMethodType) and
            // altMetafactory(..., flags) agree on argument 1 being the implementation handle.
            if (arguments.length < 2 || methodHandleRefs[arguments[1]] == 0)
            {
                return null;
            }
            int impl = methodHandleRefs[arguments[1]];
            int implNameAndType = refNameAndTypes[impl];
            String descriptor = text(nameAndTypeDescriptors[indyNameAndTypes[poolIndex]]);
            return new Callback(returnTypeOf(descriptor), simpleName(nameOf(classNames, refOwners[impl])),
                text(nameAndTypeNames[implNameAndType]), text(nameAndTypeDescriptors[implNameAndType]));
        }

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
    }

    /** The simple name of a descriptor's return type, e.g. {@code (I)La/b/C;} -> {@code C}. */
    private static String returnTypeOf(String descriptor)
    {
        int close = descriptor.lastIndexOf(')');
        if (close < 0 || close + 1 >= descriptor.length() || descriptor.charAt(close + 1) != 'L')
        {
            return ""; //$NON-NLS-1$
        }
        String internal = descriptor.substring(close + 2, descriptor.length() - 1);
        return simpleName(internal);
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
