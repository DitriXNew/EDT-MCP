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
 * single point nobody checks is forgotten the same way. This reads the compiled classes instead.
 *
 * <h2>The model</h2>
 * A mutation runs when the method holding it runs, so the question is which methods
 * {@code executeOnUiThread} can reach. The walk covers the whole NEST - the tool's class and every
 * class compiled inside it, anonymous ones included - and follows three kinds of edge:
 * <ul>
 * <li>an ordinary call ({@code invokevirtual/special/static/interface}) into the nest;</li>
 * <li>a lambda / method reference, resolved through {@code BootstrapMethods} to the method that
 * actually holds the body;</li>
 * <li>ANY reference to a nest member - construction, a constructor reference, a static call that
 * triggers its {@code <clinit>}: everything that class declares becomes reachable, because whoever
 * holds the instance decides when to run it. Modelling those JVM edges one at a time is how a blind
 * spot gets built, so the coarse rule is the safe one. That is what makes an anonymous
 * {@code Supplier} a followed edge rather than a hiding place.</li>
 * </ul>
 *
 * <h2>Why the gate's own callback may be skipped, and only then</h2>
 * A {@code DeleteWrite} body runs only when something invokes it, so following it from its creator
 * would report every branch as ungated. It is skipped - but the skip has to be EARNED, or it becomes
 * the hole it is meant to close. Four rules together:
 * <ul>
 * <li>the body is left unfollowed only when the method that created the callback also calls a gate
 * entry point ({@link #GATE_ENTRIES}, each proven to reach the gate). A method that builds a
 * {@code DeleteWrite} and never goes near the gate is walked like any other code;</li>
 * <li>the only thing that may INVOKE {@code DeleteWrite.perform} is the gate, and a method HANDLE on
 * it counts as an invocation. Converting the callback to some other functional interface
 * ({@code write::perform}) is the one way to run it without an invoke instruction;</li>
 * <li>the first thing that runs after a callback is BUILT must be a gate entry point. Anything that
 * can hold on to the value - a local, a helper taking {@code Object}, a second callback standing in
 * for it at the gate call - would put the write where nothing here reads it, and no rule about the
 * method as a whole can tell those apart without tracking which value went where;</li>
 * <li>the same applies to a nest member that IMPLEMENTS the callback type: referencing it is a
 * followed edge unless its creator hands it to the gate.</li>
 * </ul>
 * The direction is deliberate: where the rules cannot tell, they treat the write as ungated. Handing
 * the gate a callback that wraps another one ({@code deleteWithConsent(preview, w::get)}), or a
 * method reference into a helper object the branch also constructs
 * ({@code deleteWithConsent(preview, plan::apply)}), is therefore reported although it would in fact
 * be safe - modelling that away would buy nothing, since a branch hands its write to the gate
 * directly.
 *
 * <h2>What counts as a write</h2>
 * Two answers, and the second is the one that has to hold for code nobody has written yet:
 * <ul>
 * <li>{@link #MUTATIONS} names the calls the current branches write through. It carries a positive
 * control, so it fails loudly when a name drifts instead of silently checking nothing - but it is a
 * list, and a list only knows what is already on it;</li>
 * <li>{@link #writeCapableCall} is the fail-closed half. It does not enumerate writes; it asks the
 * opposite question - is this recognisably a READ? - first of the VERB (a setter is a setter
 * whatever the concrete EMF class is called, which a rule keyed on {@code EObject} would miss on
 * every generated one), and then of the SEAMS this codebase changes anything through at all: the BM
 * transaction helper and the BM API under it, {@code EcoreUtil} / {@code EList}, the {@code *Writer}
 * helpers, the refactoring service, the resource API. On those, anything it does not recognise as a
 * read counts as a write - which is what catches a verb nobody anticipated
 * ({@code rewriteNamespaceReferences}, {@code ensureExtInfo}, {@code configureDynamicListQuery} and
 * {@code rebindHandler} all exist in this repo today).</li>
 * </ul>
 *
 * <h2>What it still does NOT prove, stated plainly</h2>
 * <ul>
 * <li>a write through a seam in NEITHER the list nor the families, under a verb that reads like a
 * query - a brand-new helper class that is not named {@code *Writer} and does not touch BM, EMF,
 * refactoring or resources - is invisible here, and is covered only by the transaction-boundary
 * rules in CLAUDE.md;</li>
 * <li>owners are compared by SIMPLE name and by the STATIC type at the call site - that is all the
 * constant pool spells - so two same-named classes from different packages are one owner here, and a
 * family rule keyed on {@code EList} does not follow into {@code BasicEList};</li>
 * <li>{@code add}, {@code put} and {@code append} are not mutating verbs anywhere, because that is
 * what building a JSON payload looks like on every line of this tool. Adding to a containment
 * {@code EList} through a {@code List}-typed variable is therefore a read to these rules;</li>
 * <li>a callback built in a branch that then picks between two of them, rather than handing its own
 * straight over, is reported even when it is correct - the same erring-closed as above;</li>
 * <li>the ORDER assertions compare bytecode offsets. That catches the shape both #331 defects
 * actually had (the later step written above the earlier one); it is not a dominance proof, so a
 * branch that jumps over the earlier call could still pass. Both things the order is FOR are pinned
 * behaviourally as well: that a refusal runs no write, and that the XDTO branch answers a missing
 * target without asking ({@code DeleteMetadataToolTest});</li>
 * <li>reachability is not reflection-aware.</li>
 * </ul>
 * {@link ConsentRatchetFixtures} is this test's own control: four miniature tools, one clean and
 * three hiding a write in one specific way each. The analysis is run against them too and must
 * report exactly the one that is there - otherwise a walk that reached nothing, or a mutation rule
 * that recognised nothing, would pass here for the wrong reason.
 * <p>
 * The classes are read as resources ({@link Class#getResourceAsStream}), the way
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

    /**
     * The methods a branch may hand its write to: the gate itself and the per-branch steps that only
     * build a prompt and forward. Every one of them is checked to really reach {@link #GATE}, so the
     * set cannot be widened into a bypass by adding a name to it.
     */
    private static final Set<String> GATE_ENTRIES =
        Set.of(GATE, "gateFormMemberDelete", "gateFormObjectDelete", "gateXdtoMemberDelete"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The callback type a branch hands to the gate, relative to its declaring class. */
    private static final String WRITE_CALLBACK_SUFFIX = "$DeleteWrite"; //$NON-NLS-1$

    /** The consent seam the gate asks through. */
    private static final String ASK = "DeleteMetadataTool$ConsentRequester#request"; //$NON-NLS-1$

    /** The gate singleton: only the production constructor's lambda may touch it. */
    private static final String SINGLETON = "DestructiveConsentGate#getInstance"; //$NON-NLS-1$

    /** ... and only there may consent actually be requested from it. */
    private static final String REQUIRE_CONSENT = "DestructiveConsentGate#requireConsent"; //$NON-NLS-1$

    /**
     * Every call through which this tool changes something TODAY: the md-refactoring, the two form
     * writers, the BM write transaction, the on-disk export and the physical removal of a form's
     * folder. None of them may be reachable without the gate. This list is the named half of the
     * check; {@link #writeCapableCall} is the half that also covers what is not on it.
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

    private static final String XDTO_GATE = "DeleteMetadataTool#gateXdtoMemberDelete"; //$NON-NLS-1$

    /** What the lookup returns: the branch must PASS ONE ON, never make one up. */
    private static final String XDTO_LOOKUP_TYPE = "DeleteMetadataTool$XdtoLookup"; //$NON-NLS-1$

    // ---- the tool ------------------------------------------------------------------------------

    @Test
    public void theGateSingletonIsConsultedInExactlyOnePlace()
    {
        // methodsReaching, not methodsCalling: DestructiveConsentGate::getInstance handed on as a
        // Supplier is a second gate access with no invoke instruction to find.
        Nest nest = readTool();
        Set<String> asks = nest.methodsReaching(SINGLETON);
        Set<String> requires = nest.methodsReaching(REQUIRE_CONSENT);

        assertEquals("the destructive-consent singleton must be reached from exactly one method of " //$NON-NLS-1$
            + SELF + " - the production constructor's requester lambda. Found: " + asks //$NON-NLS-1$
            + ". A branch with its own gate call is a branch that can drift: its own preview, its own " //$NON-NLS-1$
            + "denial message, its own ordering relative to validation - and one that cannot be driven " //$NON-NLS-1$
            + "by the ConsentRequester test seam. Route it through " + GATE + " instead (issue #331).", //$NON-NLS-1$ //$NON-NLS-2$
            1, asks.size());
        assertEquals("consent must be REQUESTED only where the singleton is obtained", asks, requires); //$NON-NLS-1$

        String gate = nest.requireSingleDeclaration(SELF, GATE);
        assertTrue(GATE + " must ask through the ConsentRequester seam, never the singleton - " //$NON-NLS-1$
            + "otherwise no unit test can drive a refusal", //$NON-NLS-1$
            !asks.contains(gate) && nest.firstOffsetOf(gate, ASK) >= 0);
    }

    /**
     * Every name the walk trusts as "handed to the gate" has to earn it. Without this, widening
     * {@link #GATE_ENTRIES} - or renaming a branch step to look like one - would silently make a
     * whole branch's write invisible.
     */
    @Test
    public void everyGateEntryPointReallyReachesTheGate()
    {
        Nest nest = readTool();
        for (String entry : GATE_ENTRIES)
        {
            String node = nest.requireSingleDeclaration(SELF, entry);
            if (GATE.equals(entry))
            {
                continue;
            }
            assertTrue(entry + "() is trusted as a way to reach the consent gate, but it does not " //$NON-NLS-1$
                + "call " + GATE + "(). Either route it through the gate or take it out of " //$NON-NLS-1$ //$NON-NLS-2$
                + "GATE_ENTRIES - as long as it is listed there, a write handed to it counts as " //$NON-NLS-1$
                + "authorized (issue #331).", //$NON-NLS-1$
                nest.firstOffsetOf(node, SELF + '#' + GATE) >= 0);
        }
    }

    @Test
    public void noBranchCanReachAWriteWithoutPassingThroughTheGate()
    {
        Nest nest = readTool();
        Analysis analysis = analyse(nest, SELF);

        // Positive control: a mutation name that no longer occurs would make this test's failure mode
        // identical to its pass - it would guard nothing and stay green forever.
        for (String mutation : MUTATIONS)
        {
            assertTrue("the mutation entry point '" + mutation + "' no longer occurs in " + SELF //$NON-NLS-1$ //$NON-NLS-2$
                + ": this ratchet would be checking a name that is not there. Update " //$NON-NLS-1$
                + "MUTATIONS to the call the branch really writes through.", //$NON-NLS-1$
                !nest.methodsCalling(mutation).isEmpty());
        }
        // ... and the same for the mechanism itself: if no callback of the gate's type were found, the
        // walk below would be a plain call graph and would pass for the wrong reason.
        assertTrue("no lambda of type " + SELF + WRITE_CALLBACK_SUFFIX + " was found in " + SELF //$NON-NLS-1$ //$NON-NLS-2$
            + ": either BootstrapMethods parsing is broken or no branch hands its write to the gate", //$NON-NLS-1$
            nest.hasCallbackOfType(SELF + WRITE_CALLBACK_SUFFIX));
        // ... and for the nest: reading only the tool's own class file would hide every anonymous body.
        assertTrue("only " + nest.classes + " was parsed: the nest members were not found, so a write " //$NON-NLS-1$ //$NON-NLS-2$
            + "compiled into an anonymous class would be invisible", nest.classes.size() > 1); //$NON-NLS-1$

        assertTrue("nothing was reached from " + ENTRY + "(): the call-graph walk is broken, and a " //$NON-NLS-1$ //$NON-NLS-2$
            + "reachability ratchet that reaches nothing proves nothing", analysis.ungated.size() > 1); //$NON-NLS-1$
        assertTrue(ENTRY + "() must still reach the authorization point", //$NON-NLS-1$
            analysis.ungated.contains(nest.requireSingleDeclaration(SELF, GATE)));

        assertTrue("these methods are reachable from " + ENTRY + "() without passing through the " //$NON-NLS-1$ //$NON-NLS-2$
            + "gate's callback AND perform a mutation, so the write happens whether or not consent " //$NON-NLS-1$
            + "was granted: " + analysis.escapes + ". Every delete branch must hand its write to " //$NON-NLS-1$ //$NON-NLS-2$
            + GATE + " as a DeleteWrite callback (issue #331) - see how deleteFormObject does it.", //$NON-NLS-1$
            analysis.escapes.isEmpty());

        assertTrue("a " + SELF + WRITE_CALLBACK_SUFFIX + " is built here and the next thing that " //$NON-NLS-1$ //$NON-NLS-2$
            + "runs is not an authorization step: " + analysis.unconsumed + ". Its body is exempted " //$NON-NLS-1$ //$NON-NLS-2$
            + "from the walk on the strength of its TYPE, so anything that can hold on to it - a " //$NON-NLS-1$
            + "local, a helper taking Object, a second callback standing in for it - puts the whole " //$NON-NLS-1$
            + "mutation somewhere nothing here reads. Hand it to one of " + GATE_ENTRIES //$NON-NLS-1$
            + " as the very next call (issue #331).", analysis.unconsumed.isEmpty());
    }

    @Test
    public void theWriteCallbackIsInvokedOnlyByTheGateAndOnlyAfterAsking()
    {
        Nest nest = readTool();
        String gate = nest.requireSingleDeclaration(SELF, GATE);
        String callback = SELF + WRITE_CALLBACK_SUFFIX + "#perform"; //$NON-NLS-1$
        Set<String> invokers = nest.methodsReaching(callback);

        assertEquals("a DeleteWrite callback must be invoked ONLY by " + GATE + ": that single " //$NON-NLS-1$ //$NON-NLS-2$
            + "invocation is what makes 'reachable only through the callback' mean 'reachable only " //$NON-NLS-1$
            + "after ALLOW'. A method REFERENCE to it counts - passing write::perform on as some " //$NON-NLS-1$
            + "other functional interface runs the write with no invoke instruction in sight. " //$NON-NLS-1$
            + "Found: " + invokers, Set.of(gate), invokers); //$NON-NLS-1$

        int asked = nest.firstOffsetOf(gate, ASK);
        int wrote = nest.firstOffsetOf(gate, callback);
        assertTrue(GATE + " no longer asks for consent", asked >= 0); //$NON-NLS-1$
        assertTrue(GATE + " invokes the write at bytecode offset " + wrote + ", BEFORE it asks at " //$NON-NLS-1$ //$NON-NLS-2$
            + asked + ". The mutation must run only after an ALLOW.", asked < wrote); //$NON-NLS-1$
    }

    @Test
    public void theXdtoBranchResolvesItsTargetBeforeItAsks()
    {
        // The ordering defect #331 recorded: this branch asked first and looked the member up only
        // inside the write transaction, so a typo in the member name raised a destructive prompt at a
        // human and answered "not found" only after it had been dealt with. That the lookup's RESULT
        // then decides whether to ask at all is a separate, behavioural pin (DeleteMetadataToolTest):
        // an offset comparison alone would stay green if the answer were simply ignored.
        Nest nest = readTool();
        String branch = nest.requireSingleDeclaration(SELF, XDTO_BRANCH);
        int lookup = nest.firstOffsetOf(branch, XDTO_LOOKUP);
        int gate = nest.firstOffsetOf(branch, XDTO_GATE);

        assertTrue(XDTO_BRANCH + "() no longer looks its target up before writing: a typo would " //$NON-NLS-1$
            + "reach the gate", lookup >= 0); //$NON-NLS-1$
        assertTrue(XDTO_BRANCH + "() no longer goes through " + XDTO_GATE, gate >= 0); //$NON-NLS-1$
        assertTrue(XDTO_BRANCH + "() asks for consent at bytecode offset " + gate + " BEFORE it " //$NON-NLS-1$ //$NON-NLS-2$
            + "resolves the target at " + lookup + ". Resolve first: a delete that can only answer " //$NON-NLS-1$
            + "'not found' must never raise a destructive prompt (issue #331).", lookup < gate); //$NON-NLS-1$

        // Running the lookup first proves nothing if its ANSWER can be thrown away: passing a
        // hand-built XdtoLookup would satisfy every assertion above and the unit tests too, and the
        // prompt would be back for a target that is not there. The branch may not build one.
        assertTrue(XDTO_BRANCH + "() constructs an " + XDTO_LOOKUP_TYPE + " of its own, so what it " //$NON-NLS-1$ //$NON-NLS-2$
            + "hands to " + XDTO_GATE + " need not be what the lookup found. The only " //$NON-NLS-1$ //$NON-NLS-2$
            + "lookup outcome it may pass on is the one it got back (issue #331 review).", //$NON-NLS-1$
            nest.firstOffsetOf(branch, XDTO_LOOKUP_TYPE + "#<init>") < 0); //$NON-NLS-1$
    }

    // ---- the ratchet's own controls ------------------------------------------------------------

    /**
     * The clean shape must come back clean. Without this, a check that flagged everything would pass
     * the three bypass tests below and still be worthless.
     */
    @Test
    public void theAnalysisReportsNothingAgainstAGatedFixture()
    {
        Analysis clean = analyseFixture(ConsentRatchetFixtures.Gated.class);

        assertTrue("the compliant fixture hands its only write to the gate, yet the analysis " //$NON-NLS-1$
            + "reported: " + clean.escapes + ". A check that flags a correct shape cannot be used to " //$NON-NLS-1$ //$NON-NLS-2$
            + "judge the real tool.", clean.escapes.isEmpty()); //$NON-NLS-1$
        assertTrue("the compliant fixture hands its callback straight to the gate: " //$NON-NLS-1$
            + clean.unconsumed, clean.unconsumed.isEmpty());
        assertEquals("only the gate may invoke the compliant fixture's callback", //$NON-NLS-1$
            1, clean.callbackInvokers.size());
    }

    /**
     * The callback handed to a helper the walk never reads, and to the gate afterwards. Nothing
     * inside the analysed classes invokes {@code perform}, and the counts all balance.
     */
    @Test
    public void theAnalysisCatchesAWriteCallbackHandedToSomethingOtherThanTheGate()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.LeakedCallbackBypass.class);

        assertTrue("the fixture passes its callback to a helper class before asking for consent. " //$NON-NLS-1$
            + "That helper is not parsed here and never will be, so the only thing that can catch " //$NON-NLS-1$
            + "this is requiring the gate to be the very next thing that runs.", //$NON-NLS-1$
            !bypass.unconsumed.isEmpty());
        assertTrue("... and it is the only thing that catches it: the walk is satisfied here.", //$NON-NLS-1$
            bypass.escapes.isEmpty());
    }

    /**
     * The escape hatch's own escape hatch: a callback that IS handed to the gate, and is also turned
     * into another functional interface by method reference and run directly.
     */
    @Test
    public void theAnalysisCatchesAWriteRunThroughAMethodReferenceToTheCallback()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.MethodHandleBypass.class);

        assertTrue("this fixture satisfies every structural rule - it creates one callback and hands " //$NON-NLS-1$
            + "it to the gate - and still runs the write itself through write::perform. Counting a " //$NON-NLS-1$
            + "method HANDLE on the callback as an invocation is the only thing that sees it, and " //$NON-NLS-1$
            + "the analysis reported invokers " + bypass.callbackInvokers, //$NON-NLS-1$
            bypass.callbackInvokers.size() > 1);
        assertTrue("... and it is the ONLY thing that sees it: the walk exempts this callback " //$NON-NLS-1$
            + "legitimately, so the escape set is empty here. " + bypass.escapes, //$NON-NLS-1$
            bypass.escapes.isEmpty());
    }

    /**
     * The reported shape, verbatim: build a {@code DeleteWrite}, hand it to nobody, and run it as a
     * {@code Supplier}. Exempting a callback's body because of its TYPE alone loses this whole branch.
     */
    @Test
    public void theAnalysisCatchesAWriteCallbackThatIsNeverHandedToTheGate()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.UnconsumedCallbackBypass.class);

        assertTrue("the fixture never calls its gate, so the callback it builds may not be exempted " //$NON-NLS-1$
            + "from the walk - the write inside it has to be reported: " + bypass.escapes, //$NON-NLS-1$
            !bypass.escapes.isEmpty());
        assertTrue("... and the callback itself has to be reported as unconsumed: " //$NON-NLS-1$
            + bypass.unconsumed, !bypass.unconsumed.isEmpty()); //$NON-NLS-1$
    }

    /** A write compiled into an anonymous class: a different class file, invisible without the nest. */
    @Test
    public void theAnalysisCatchesAWriteHiddenInAnAnonymousClass()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.AnonymousClassBypass.class);

        assertTrue("the write sits in the get() of an anonymous Supplier the branch runs itself. " //$NON-NLS-1$
            + "Reading only the fixture's own class file, or refusing to follow construction of a " //$NON-NLS-1$
            + "nest member, makes it disappear - the analysis found no escape at all.", //$NON-NLS-1$
            !bypass.escapes.isEmpty());
    }

    /** A write through an API nobody listed: only the verb rule can see this one. */
    @Test
    public void theAnalysisCatchesAWriteThroughAnUnlistedMutationApi()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.UnlistedMutationApiBypass.class);

        assertTrue("the fixture removes an EObject with raw EMF, outside any transaction and " //$NON-NLS-1$
            + "outside MUTATIONS. If only the named list decided what a write is, this branch would " //$NON-NLS-1$
            + "be invisible while the list's own positive control stayed green - which is exactly " //$NON-NLS-1$
            + "the failure mode the verb rule exists for.", !bypass.escapes.isEmpty()); //$NON-NLS-1$
    }

    /** A generated setter on a concrete model class: no family owns it, only the verb sees it. */
    @Test
    public void theAnalysisCatchesAWriteThroughASetterOnAConcreteType()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.ConcreteSetterBypass.class);

        assertTrue("the fixture renames a classifier through EClass.setName. The constant pool " //$NON-NLS-1$
            + "spells the STATIC type, so a rule keyed on EObject/EList sees nothing here - and " //$NON-NLS-1$
            + "every generated EMF setter in the metamodel has this exact shape.", //$NON-NLS-1$
            !bypass.escapes.isEmpty());
    }

    /**
     * The primitive both the singleton assertion and the invoker assertion stand on: a method HANDLE
     * has to count as reaching its target. If {@code methodsReaching} were only
     * {@code methodsCalling}, every "reached from exactly one place" claim would be about invoke
     * instructions alone, and a handle would slip past all of them.
     */
    @Test
    public void aMethodHandleCountsAsReachingItsTarget()
    {
        Nest fixtures = read(ConsentRatchetFixtures.class);
        String perform = binaryName(ConsentRatchetFixtures.MethodHandleBypass.class)
            + WRITE_CALLBACK_SUFFIX + "#perform"; //$NON-NLS-1$

        Set<String> calling = fixtures.methodsCalling(perform);
        Set<String> reaching = fixtures.methodsReaching(perform);

        assertTrue("the fixture takes a method handle on " + perform + ", so 'reaching' must be a " //$NON-NLS-1$ //$NON-NLS-2$
            + "STRICT superset of 'calling'. calling=" + calling + " reaching=" + reaching, //$NON-NLS-1$ //$NON-NLS-2$
            reaching.containsAll(calling) && reaching.size() > calling.size());
    }

    /** A write whose VERB nobody anticipated, on a helper that can write: the family rule's case. */
    @Test
    public void theAnalysisCatchesAWriteThroughAnUnlistedVerbOnAWriterHelper()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.UnlistedWriterVerbBypass.class);

        assertTrue("the fixture calls XdtoWriter.rewriteNamespaceReferences, which really does " //$NON-NLS-1$
            + "mutate its argument. It is on no list, and 'rewrite' is no set/remove/clear - the " //$NON-NLS-1$
            + "only thing that reports it is treating a helper that CAN write as denied until the " //$NON-NLS-1$
            + "call reads like a read.", !bypass.escapes.isEmpty()); //$NON-NLS-1$
    }

    /** The write one edge past the callback body: a constructor reference, run by a Runnable. */
    @Test
    public void theAnalysisCatchesAWriteReachedThroughAConstructorReference()
    {
        Analysis bypass = analyseFixture(ConsentRatchetFixtures.ConstructorReferenceBypass.class);

        assertTrue("Deferred::new links only to <init>; run() is invoked through Runnable, whose " //$NON-NLS-1$
            + "owner this analysis never reads. Following the callback BODY and stopping there " //$NON-NLS-1$
            + "leaves the write invisible - referencing a nested class has to expand it.", //$NON-NLS-1$
            !bypass.escapes.isEmpty());
    }

    // ---- the analysis ---------------------------------------------------------------------------

    /** What one run of the walk found. */
    private static final class Analysis
    {
        /** Methods reachable from the entry point without going through the gate's callback. */
        final Set<String> ungated;

        /** Of those, the ones that write, and what they write through. */
        final Map<String, Set<String>> escapes;

        /** Places where a write callback is built and not handed straight to the gate. */
        final Set<String> unconsumed;

        /** Methods that invoke the write callback, by call OR by method handle. */
        final Set<String> callbackInvokers;

        Analysis(Set<String> ungated, Map<String, Set<String>> escapes, Set<String> unconsumed,
            Set<String> callbackInvokers)
        {
            this.ungated = ungated;
            this.escapes = escapes;
            this.unconsumed = unconsumed;
            this.callbackInvokers = callbackInvokers;
        }
    }

    private static Analysis analyse(Nest nest, String unit)
    {
        return analyse(nest, unit, MUTATIONS, GATE_ENTRIES);
    }

    /**
     * Runs the whole model over one unit (a class and everything compiled inside it).
     *
     * @param nest the parsed classes
     * @param unit the analysed class, as the constant pool spells it
     * @param mutations the calls that count as a write by name
     * @param gateEntries the methods a write callback may legitimately be handed to
     * @return what the walk found
     */
    private static Analysis analyse(Nest nest, String unit, List<String> mutations, Set<String> gateEntries)
    {
        String writeType = unit + WRITE_CALLBACK_SUFFIX;
        Set<String> ungated = nest.reachableWithout(nest.requireSingleDeclaration(unit, ENTRY), unit,
            writeType, gateEntries);

        Map<String, Set<String>> escapes = new LinkedHashMap<>();
        for (String method : ungated)
        {
            for (Call call : nest.callsIn(method))
            {
                // Only EXTERNAL calls are judged. A call into the analysed classes is FOLLOWED, so
                // asking whether its name looks like a mutation would double-count it - and would
                // read a name this ratchet has no business reading (collectRemovedMembers is a walk).
                if (!Nest.inUnit(call.owner, unit))
                {
                    addWrite(escapes, method, call.target(), call.name, mutations);
                }
            }
            // A method REFERENCE to a mutation ({@code refactoring::perform} handed to a Runnable the
            // branch then runs) never appears as a call, and its target is not one of our methods, so
            // the walk cannot step into it. It still names a mutation - count it as one.
            for (Callback callback : nest.callbacksIn(method))
            {
                if (!writeType.equals(callback.type) && !Nest.inUnit(callback.implOwner, unit))
                {
                    addWrite(escapes, method, callback.target(), callback.implMethod, mutations);
                }
            }
        }
        return new Analysis(ungated, escapes,
            nest.callbacksNotHandedStraightToTheGate(unit, writeType, gateEntries),
            nest.methodsReaching(writeType + "#perform")); //$NON-NLS-1$
    }

    private static Analysis analyseFixture(Class<?> fixture)
    {
        // The fixtures write through their own sink, so what they prove does not depend on what the
        // real tool happens to call - except for the family rule, which is the point of one of them.
        return analyse(read(ConsentRatchetFixtures.class), binaryName(fixture),
            List.of("ConsentRatchetFixtures$Sink#mutate"), Set.of(GATE)); //$NON-NLS-1$
    }

    private static void addWrite(Map<String, Set<String>> escapes, String method, String target, String name,
        List<String> mutations)
    {
        if (mutations.contains(target) || writeCapableCall(target, name))
        {
            escapes.computeIfAbsent(method, unused -> new TreeSet<>()).add(target);
        }
    }

    /**
     * Whether {@code target} can change something. This is the fail-closed half of "what counts as a
     * write": instead of listing the calls we know about it asks the OPPOSITE question - is this
     * recognisably a read? - about the verb, and about the seams this codebase changes anything
     * through at all. A new mutation reached from an ungated branch therefore fails this ratchet the
     * day it is written, without anyone remembering to extend {@link #MUTATIONS}.
     *
     * @param target the callee, as {@code Owner#method}
     * @param name the callee's bare method name
     * @return whether the call may change something
     */
    private static boolean writeCapableCall(String target, String name)
    {
        String owner = target.substring(0, target.indexOf('#'));
        if (LOCAL_VALUES.contains(owner))
        {
            return false; // assembling a message or a JSON payload changes nothing outside the method
        }
        // The verb first, the owner second. The constant pool spells the STATIC type, so keying the
        // EMF mutators on the interface name would miss every generated one: EObject.eSet and
        // Import.setNamespace are the same act. Iterator.remove belongs here too - on the iterator of
        // a containment EList it writes straight through to the model. The infix forms catch the
        // implementation classes' own spelling (InternalEList.basicRemove).
        if (startsWithAny(name, "set", "unset", "remove", "insert", "move", "rename", "replace", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "eSet", "eUnset", "eInvoke", "save", "persist", "commit") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            || containsAny(name, "Remove", "Unset", "Clear") || "clear".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            return true;
        }
        // The transaction helper: reads and rolled-back reads are the only non-writing entry points.
        if ("BmTransactions".equals(owner)) //$NON-NLS-1$
        {
            return !"read".equals(name) && !"executeAndRollback".equals(name); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The BM API under it: this tool must not touch it directly at all (CLAUDE.md rule #1).
        if ("IBmModel".equals(owner) || "IBmEngine".equals(owner)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        // Everything below is DENY-by-default on a family that can write, so a helper whose verb
        // nobody anticipated - rewriteNamespaceReferences, ensureExtInfo, configureDynamicListQuery,
        // rebindHandler all exist in this repo today - counts as a write until it reads like a read.
        if ("IBmTransaction".equals(owner) || "EcoreUtil".equals(owner) || "EList".equals(owner) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || RESOURCES.contains(owner) || (owner.endsWith("Writer") && !JDK_WRITERS.contains(owner))) //$NON-NLS-1$
        {
            return !readShaped(name);
        }
        // The refactoring service: BUILDING a refactoring is inert, only running it writes - which is
        // why 'create' is a read here and nowhere else, and only while it stays a pure factory.
        if (owner.startsWith("IRefactoring") || owner.endsWith("RefactoringService")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return !readShaped(name)
                && !(name.startsWith("create") && !containsAny(name, "Perform", "Apply", "Execute")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return false;
    }

    /** Owners whose mutators are local bookkeeping: a message being built, never the model. */
    private static final Set<String> LOCAL_VALUES =
        Set.of("String", "StringBuilder", "StringBuffer"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * ... and the {@code *Writer}s that write into MEMORY. The on-disk ones are deliberately absent:
     * a metadata file rewritten through a {@code FileWriter} is exactly as destructive as one
     * rewritten through the model.
     */
    private static final Set<String> JDK_WRITERS = Set.of("StringWriter", "CharArrayWriter"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Set<String> RESOURCES = Set.of("IResource", "IFolder", "IFile", "IContainer", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "IProject", "IWorkspaceRoot", "IWorkspace"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Whether a method NAME reads like a query. Deliberately about the name and nothing else: on a
     * family that can write, anything this does not recognise is treated as a write, so the burden is
     * on a new helper to be named like the read it is. Kept narrow on purpose - a bare {@code to}
     * prefix would have made {@code IResource.touch} a read, and {@code getOrCreate...} is a real
     * shape in this repo for a getter that writes.
     *
     * @param name the callee's bare method name
     * @return whether it reads like a query
     */
    private static boolean readShaped(String name)
    {
        if (name.contains("OrCreate")) //$NON-NLS-1$
        {
            return false;
        }
        return startsWithAny(name, "get", "is", "has", "find", "read", "resolve", "parse", "copy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "desc", "format", "kind", "preview", "exists", "members", "accept", "toString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "toArray", "size", "iterator", "contains", "indexOf", "stream", "forEach", "equals", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "hashCode") //$NON-NLS-1$
            || endsWithAny(name, "Error", "Advice", "Hint", "Message", "For", "Of"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    private static boolean containsAny(String name, String... parts)
    {
        for (String part : parts)
        {
            if (name.contains(part))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String name, String... prefixes)
    {
        for (String prefix : prefixes)
        {
            if (name.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String name, String... suffixes)
    {
        for (String suffix : suffixes)
        {
            if (name.endsWith(suffix))
            {
                return true;
            }
        }
        return false;
    }

    /** The class name as the constant pool spells it: {@code Outer$Inner}, no package. */
    private static String binaryName(Class<?> clazz)
    {
        String name = clazz.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Nest readTool()
    {
        return read(DeleteMetadataTool.class);
    }

    private static Nest read(Class<?> root)
    {
        try
        {
            return Nest.read(root);
        }
        catch (IOException e)
        {
            fail("could not read the compiled " + root.getSimpleName() + ": " + e //$NON-NLS-1$ //$NON-NLS-2$
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

        /** The callee's node key: an owner AND a descriptor, because overloads are different methods. */
        String node()
        {
            return owner + '#' + name + descriptor;
        }
    }

    /**
     * One lambda / method reference created by an {@code invokedynamic}: the functional interface it
     * produces, and the method that actually holds the body (from {@code BootstrapMethods}).
     */
    private static final class Callback
    {
        private final int offset;

        private final String type;

        private final String implOwner;

        private final String implMethod;

        private final String implDescriptor;

        Callback(int offset, String type, String implOwner, String implMethod, String implDescriptor)
        {
            this.offset = offset;
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

        /** The body's node key. */
        String node()
        {
            return implOwner + '#' + implMethod + implDescriptor;
        }
    }

    /** One method of one class: its ordinary calls and the callbacks it creates. */
    private static final class MethodBody
    {
        private final List<Call> calls = new ArrayList<>();

        private final List<Callback> callbacks = new ArrayList<>();
    }

    /**
     * A class and every class compiled inside it, parsed together into one graph. Reading the nest -
     * not just the outer class - is what makes an anonymous inner body part of the model instead of a
     * blind spot.
     */
    private static final class Nest
    {
        /** The parsed classes, as the constant pool spells them. */
        final Set<String> classes = new LinkedHashSet<>();

        /** Method bodies by node key ({@code Owner#name+descriptor}). */
        private final Map<String, MethodBody> methods = new LinkedHashMap<>();

        /** The methods each class declares, in declaration order. */
        private final Map<String, Set<String>> methodsOf = new LinkedHashMap<>();

        /** How many methods each class declares under a bare name. */
        private final Map<String, Integer> declarations = new LinkedHashMap<>();

        /** Node keys by {@code Owner#name}, so an assertion can name a method. */
        private final Map<String, Set<String>> nodesByName = new LinkedHashMap<>();

        /** The interfaces each class implements, by simple name. */
        private final Map<String, Set<String>> interfacesOf = new LinkedHashMap<>();

        /** Every method that contains a call to {@code target} ({@code Owner#method}). */
        Set<String> methodsCalling(String target)
        {
            Set<String> found = new TreeSet<>();
            for (Map.Entry<String, MethodBody> method : methods.entrySet())
            {
                for (Call call : method.getValue().calls)
                {
                    if (target.equals(call.target()))
                    {
                        found.add(method.getKey());
                    }
                }
            }
            return found;
        }

        /**
         * Every method that can RUN {@code target}: one that calls it, and one that turns it into a
         * functional interface by method reference. The second form runs the callback just as
         * effectively and leaves no invoke instruction behind, so a check that ignored it would be
         * satisfied by {@code Supplier<String> s = write::perform; s.get();}.
         *
         * @param target the callee, as {@code Owner#method}
         * @return the methods that reach it
         */
        Set<String> methodsReaching(String target)
        {
            Set<String> found = new TreeSet<>(methodsCalling(target));
            for (Map.Entry<String, MethodBody> method : methods.entrySet())
            {
                for (Callback callback : method.getValue().callbacks)
                {
                    if (target.equals(callback.target()))
                    {
                        found.add(method.getKey());
                    }
                }
            }
            return found;
        }

        List<Call> callsIn(String node)
        {
            MethodBody body = methods.get(node);
            return body == null ? List.of() : body.calls;
        }

        List<Callback> callbacksIn(String node)
        {
            MethodBody body = methods.get(node);
            return body == null ? List.of() : body.callbacks;
        }

        /** Whether any method creates a callback of {@code type} - the walk's own positive control. */
        boolean hasCallbackOfType(String type)
        {
            for (MethodBody body : methods.values())
            {
                for (Callback callback : body.callbacks)
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
         * The node key of {@code owner}'s {@code method}, failing when it is overloaded: an ordering
         * assertion names a method, and across two same-named methods an offset comparison would read
         * unrelated instructions.
         *
         * @param owner the declaring class
         * @param method the bare method name an assertion is about to read
         * @return its single node key
         */
        String requireSingleDeclaration(String owner, String method)
        {
            String key = owner + '#' + method;
            int declared = declarations.getOrDefault(key, 0);
            assertEquals("an assertion names " + key + "(), so it must be declared " //$NON-NLS-1$ //$NON-NLS-2$
                + "exactly once; split the assertion per descriptor before overloading it", //$NON-NLS-1$
                1, declared);
            return nodesByName.get(key).iterator().next();
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

        /** Whether {@code node} hands anything to one of the gate's entry points. */
        private boolean callsAGate(String node, String unit, Set<String> gateEntries)
        {
            for (Call call : callsIn(node))
            {
                if (inUnit(call.owner, unit) && gateEntries.contains(call.name))
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * Every place a write callback is built without going STRAIGHT into the gate.
         *
         * <p>Weaker phrasings of this were tried and are all dodgeable. "Calls a gate somewhere in the
         * same method" lets a branch build two callbacks, authorize one and run the other. "Is not
         * passed to anything but a gate" reads the callee's descriptor, so handing it to a helper
         * taking {@code Object} hides it. Both fail for the same reason: without tracking WHICH value
         * went where, any rule about the method as a whole can be satisfied by a second callback.</p>
         *
         * <p>So the rule is about the value's only safe lifetime: the first invocation after the one
         * that CREATES the callback must be an authorization step. Then it was never stored, never
         * passed anywhere else, and no second callback can stand in for it - which is how every branch
         * in this tool already writes it. A branch that wants a named local instead has to make the
         * gate call the next thing it does.</p>
         *
         * @param unit the analysed class
         * @param writeType the gate's callback type
         * @param gateEntries the methods a callback may legitimately be handed to
         * @return the offending {@code method -> what it did with it instead} pairs
         */
        Set<String> callbacksNotHandedStraightToTheGate(String unit, String writeType,
            Set<String> gateEntries)
        {
            Set<String> offenders = new TreeSet<>();
            for (Map.Entry<String, MethodBody> method : methods.entrySet())
            {
                if (!inUnit(ownerOf(method.getKey()), unit))
                {
                    continue;
                }
                List<Integer> created = new ArrayList<>();
                for (Callback callback : method.getValue().callbacks)
                {
                    if (writeType.equals(callback.type))
                    {
                        created.add(callback.offset);
                    }
                }
                for (Call call : method.getValue().calls)
                {
                    if ("<init>".equals(call.name) && implementsType(call.owner, writeType)) //$NON-NLS-1$
                    {
                        created.add(call.offset); // a named / anonymous implementation of it
                    }
                }
                for (int at : created)
                {
                    Call next = firstCallAfter(method.getKey(), at);
                    if (next == null || !(inUnit(next.owner, unit) && gateEntries.contains(next.name)))
                    {
                        offenders.add(method.getKey() + " -> " //$NON-NLS-1$
                            + (next == null ? "nothing" : next.target())); //$NON-NLS-1$
                    }
                }
            }
            return offenders;
        }

        /** The first invocation instruction after {@code offset} in {@code node}, or {@code null}. */
        private Call firstCallAfter(String node, int offset)
        {
            Call best = null;
            for (Call call : callsIn(node))
            {
                if (call.offset > offset && (best == null || call.offset < best.offset))
                {
                    best = call;
                }
            }
            return best;
        }

        /**
         * The methods of this unit reachable from {@code entry} without going through the gate's
         * callback. Three kinds of edge are followed - ordinary calls, callback bodies, and the
         * declared methods of any nest member the code constructs - and only ONE is ever cut: a
         * callback of {@code gatedType} created by a method that also calls a gate entry point. Every
         * other shape is walked, so a write the model cannot account for shows up as ungated rather
         * than disappearing.
         *
         * @param entry the method to start from
         * @param unit the analysed class
         * @param gatedType the callback type whose bodies may be left unfollowed
         * @param gateEntries the methods a callback may legitimately be handed to
         * @return the reachable method nodes, {@code entry} included
         */
        Set<String> reachableWithout(String entry, String unit, String gatedType, Set<String> gateEntries)
        {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            seen.add(entry);
            queue.add(entry);
            while (!queue.isEmpty())
            {
                String from = queue.poll();
                boolean authorizes = callsAGate(from, unit, gateEntries);
                for (Call call : callsIn(from))
                {
                    if (!inUnit(call.owner, unit))
                    {
                        continue;
                    }
                    if (methods.containsKey(call.node()) && seen.add(call.node()))
                    {
                        queue.add(call.node());
                    }
                    expand(call.owner, unit, gatedType, authorizes, seen, queue);
                }
                for (Callback callback : callbacksIn(from))
                {
                    if (authorizes && gatedType.equals(callback.type))
                    {
                        continue;
                    }
                    if (inUnit(callback.implOwner, unit) && methods.containsKey(callback.node())
                        && seen.add(callback.node()))
                    {
                        queue.add(callback.node());
                    }
                    // A CONSTRUCTOR reference (Deferred::new handed to a Function) links only to
                    // <init>, and whatever runs the object afterwards is owned by java.util.function
                    // - so following the body alone would stop one edge short of the write. Only for
                    // <init>: an ordinary lambda body is a SYNTHETIC method that happens to sit in
                    // the creating class, and expanding that class would follow every callback in it,
                    // gated ones included.
                    if ("<init>".equals(callback.implMethod)) //$NON-NLS-1$
                    {
                        expand(callback.implOwner, unit, gatedType, authorizes, seen, queue);
                    }
                }
            }
            return seen;
        }

        /**
         * Touching a class compiled INSIDE the analysed one hands its whole surface to whoever holds
         * it: construction, a constructor reference, a static call that runs its {@code <clinit>} -
         * the JVM has more than one way to get such a class's code running, and modelling them one at
         * a time is how a blind spot gets built. So any reference expands it. Two things are never
         * expanded: the analysed class itself (that would make the walk universal and prove nothing),
         * and an implementation of the gate's own callback type whose creator hands it to the gate.
         *
         * @param owner the referenced class
         * @param unit the analysed class
         * @param gatedType the callback type whose implementations may be left unfollowed
         * @param authorizes whether the referencing method calls a gate entry point
         * @param seen the reachable set so far
         * @param queue the walk's queue
         */
        private void expand(String owner, String unit, String gatedType, boolean authorizes,
            Set<String> seen, Deque<String> queue)
        {
            if (owner.equals(unit) || !inUnit(owner, unit)
                || (authorizes && implementsType(owner, gatedType)))
            {
                return;
            }
            for (String member : methodsOf.getOrDefault(owner, Set.of()))
            {
                if (seen.add(member))
                {
                    queue.add(member);
                }
            }
        }

        private boolean implementsType(String clazz, String type)
        {
            return interfacesOf.getOrDefault(clazz, Set.of()).contains(type);
        }

        private static boolean inUnit(String owner, String unit)
        {
            return owner.equals(unit) || owner.startsWith(unit + "$"); //$NON-NLS-1$
        }

        private static String ownerOf(String node)
        {
            return node.substring(0, node.indexOf('#'));
        }

        /**
         * Parses {@code root} and, transitively, every class compiled inside it.
         *
         * @param root the outermost class
         * @return the parsed nest
         * @throws IOException when a class resource is missing or truncated
         */
        static Nest read(Class<?> root) throws IOException
        {
            Nest nest = new Nest();
            Deque<String> pending = new ArrayDeque<>();
            pending.add(binaryName(root));
            while (!pending.isEmpty())
            {
                String name = pending.poll();
                if (!nest.classes.add(name))
                {
                    continue;
                }
                for (String member : nest.parse(root, name))
                {
                    if (!nest.classes.contains(member))
                    {
                        pending.add(member);
                    }
                }
            }
            return nest;
        }

        /**
         * Reads one class file into this nest.
         *
         * @param neighbour any class in the same package, used to resolve the resource
         * @param name the class to read, as the constant pool spells it
         * @return the nest members it declares
         * @throws IOException when the resource is missing or truncated
         */
        private Set<String> parse(Class<?> neighbour, String name) throws IOException
        {
            String resource = name + ".class"; //$NON-NLS-1$
            try (InputStream raw = neighbour.getResourceAsStream(resource))
            {
                if (raw == null)
                {
                    throw new IOException("class resource not found: " + resource); //$NON-NLS-1$
                }
                try (DataInputStream in = new DataInputStream(raw))
                {
                    return new ClassFile(this, name).parse(in);
                }
            }
        }
    }

    /** Parses one class file into a {@link Nest}. */
    private static final class ClassFile
    {
        private final Nest nest;

        private final String self;

        private String[] utf8;

        private int[] classNames;

        private int[] refOwners;

        private int[] refNameAndTypes;

        private int[] nameAndTypeNames;

        private int[] nameAndTypeDescriptors;

        /** For a CONSTANT_MethodHandle: the pool index of the ref it points at. */
        private int[] methodHandleRefs;

        /** For a CONSTANT_InvokeDynamic: its BootstrapMethods index and its NameAndType. */
        private int[] indyBootstraps;

        private int[] indyNameAndTypes;

        /** BootstrapMethods: the bootstrap MethodHandle and the static arguments, per entry. */
        private int[] bootstrapHandles = new int[0];

        private int[][] bootstrapArguments = new int[0][];

        /** Raw bodies, kept until BootstrapMethods (a CLASS attribute) has been read. */
        private final Map<String, List<byte[]>> bodies = new LinkedHashMap<>();

        /** The nest members named by this class's NestMembers / InnerClasses attributes. */
        private final Set<String> members = new LinkedHashSet<>();

        ClassFile(Nest nest, String self)
        {
            this.nest = nest;
            this.self = self;
        }

        private Set<String> parse(DataInputStream in) throws IOException
        {
            if (in.readInt() != 0xCAFEBABE)
            {
                throw new IOException("not a class file (bad magic)"); //$NON-NLS-1$
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            allocate(in.readUnsignedShort());
            readConstantPool(in);
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this class
            in.readUnsignedShort(); // super class
            readInterfaces(in);
            skipMembers(in); // fields
            readMethods(in);
            readClassAttributes(in); // BootstrapMethods lives here, AFTER the methods
            resolveBodies();
            return members;
        }

        private void allocate(int poolSize)
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

        private void readInterfaces(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            Set<String> implemented = new LinkedHashSet<>();
            for (int i = 0; i < count; i++)
            {
                implemented.add(simpleName(nameOf(classNames, in.readUnsignedShort())));
            }
            nest.interfacesOf.put(self, implemented);
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
                // Keyed by owner, name AND descriptor: two overloads are two methods, and merging them
                // would import one's mutations into the other's reachability (issue #331 review).
                String node = self + '#' + name + descriptor;
                nest.declarations.merge(self + '#' + name, 1, Integer::sum);
                nest.nodesByName.computeIfAbsent(self + '#' + name, unused -> new TreeSet<>()).add(node);
                nest.methodsOf.computeIfAbsent(self, unused -> new LinkedHashSet<>()).add(node);
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

        /** Reads the class-level attributes, keeping {@code BootstrapMethods} and the nest members. */
        private void readClassAttributes(DataInputStream in) throws IOException
        {
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++)
            {
                String attribute = text(in.readUnsignedShort());
                int length = in.readInt();
                if ("BootstrapMethods".equals(attribute)) //$NON-NLS-1$
                {
                    readBootstrapMethods(in);
                }
                else if ("NestMembers".equals(attribute)) //$NON-NLS-1$
                {
                    int count = in.readUnsignedShort();
                    for (int m = 0; m < count; m++)
                    {
                        collect(simpleName(nameOf(classNames, in.readUnsignedShort())));
                    }
                }
                else if ("InnerClasses".equals(attribute)) //$NON-NLS-1$
                {
                    // The fallback, and the only place a LOCAL class of a nested class is named on
                    // some compilers. It also lists classes this one merely REFERENCES (Map$Entry),
                    // hence the prefix filter in collect().
                    int count = in.readUnsignedShort();
                    for (int m = 0; m < count; m++)
                    {
                        collect(simpleName(nameOf(classNames, in.readUnsignedShort())));
                        skipFully(in, 6); // outer class, inner name, access flags
                    }
                }
                else
                {
                    skipFully(in, length);
                }
            }
        }

        private void collect(String name)
        {
            String root = nest.classes.iterator().next();
            if (name.startsWith(root + "$")) //$NON-NLS-1$
            {
                members.add(name);
            }
        }

        private void readBootstrapMethods(DataInputStream in) throws IOException
        {
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

        /** Walks every kept body once the constant pool AND BootstrapMethods are both available. */
        private void resolveBodies()
        {
            for (Map.Entry<String, List<byte[]>> method : bodies.entrySet())
            {
                MethodBody parsed = nest.methods.computeIfAbsent(method.getKey(), unused -> new MethodBody());
                for (byte[] code : method.getValue())
                {
                    walk(code, parsed);
                }
            }
        }

        /**
         * Walks one method body instruction by instruction (including the variable-length {@code wide}
         * / {@code tableswitch} / {@code lookupswitch} forms), so a constant-pool index that happens to
         * look like an opcode inside another instruction's operands cannot be mistaken for a call.
         *
         * @param code the method's bytecode
         * @param into collects its ordinary calls, in execution order, and the callbacks it creates
         */
        private void walk(byte[] code, MethodBody into)
        {
            int pc = 0;
            while (pc < code.length)
            {
                int opcode = code[pc] & 0xFF;
                if (opcode >= 0xB6 && opcode <= 0xB9) // invokevirtual / special / static / interface
                {
                    int ref = readUnsignedShort(code, pc + 1);
                    int nameAndType = refNameAndTypes[ref];
                    into.calls.add(new Call(pc, simpleName(nameOf(classNames, refOwners[ref])),
                        text(nameAndTypeNames[nameAndType]), text(nameAndTypeDescriptors[nameAndType])));
                }
                else if (opcode == 0xBA) // invokedynamic: creates a callback, does not run it
                {
                    Callback callback = callbackAt(pc, readUnsignedShort(code, pc + 1));
                    if (callback != null)
                    {
                        into.callbacks.add(callback);
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
        private Callback callbackAt(int offset, int poolIndex)
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
            return new Callback(offset, returnTypeOf(descriptor), simpleName(nameOf(classNames, refOwners[impl])),
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
