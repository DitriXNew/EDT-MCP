/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory model of EDT's merge-settings ("merge rules") file - the document a comparison
 * saves its per-node merge decisions into, and the one
 * {@code IComparisonManager.deserializeMergeSettings(handle, fileName)} reads back when a
 * comparison is launched.
 * <p>
 * <b>The shape is measured from the platform, not guessed</b> (bytecode of
 * {@code HierarchicalMergeSettingsSerializerService} and
 * {@code internal.compare.settings.model.MergeSettingsTree} on 2026.1.2):
 *
 * <pre>
 * &lt;Settings Format_version="2.0"&gt;
 *   &lt;Correspondences&gt;...&lt;/Correspondences&gt;      &lt;!-- optional, before OR after --&gt;
 *   &lt;MergeSettings&gt;
 *     &lt;Node Key="$$Root$$"&gt;
 *       &lt;Node Key="commonModules" MergeRule="GetFromOther"&gt;
 *         &lt;Node Key="main:other:ancestor" MergeRule="DoNotMerge"/&gt;
 *       &lt;/Node&gt;
 *     &lt;/Node&gt;
 *   &lt;/MergeSettings&gt;
 * &lt;/Settings&gt;
 * </pre>
 *
 * Three addressing levels are what the platform's own path generators produce:
 * <ol>
 * <li>the root marker {@link #ROOT_KEY};</li>
 * <li>a feature-collection node keyed by the EMF feature NAME
 * ({@code EStructuralFeature.getName()}, e.g. {@code commonModules});</li>
 * <li>a top-object node keyed {@code main:other:ancestor} - three NAMES joined by a colon
 * ({@code TopNodePathGenerator} formats {@code "%s:%s:%s"} out of {@code getMainSymlink()} /
 * {@code getOtherSymlink()} / {@code getCommonAncestorSymlink()}), with the literal
 * {@link #SIDE_ABSENT} standing for "this side has no such object". A rename therefore
 * legitimately yields three DIFFERENT names, and a one-sided add yields {@code X:NONE:X}.</li>
 * </ol>
 * Below the top object the key stops being a name: a {@code CollectionElementComparisonNode}
 * is keyed by the engine-computed {@code getPositionAfterMerge()}, i.e. a bare integer that
 * SHIFTS as soon as another rule changes. Such keys are read and reported, never authored -
 * see {@link #MAX_AUTHORABLE_DEPTH}.
 * <p>
 * <b>The file is sparse:</b> only decisions are written, so every {@code MergeRule} attribute
 * in it is a decision. (The live-session distinction {@code isMergeRuleSetByUser()} /
 * {@code isDefaultMergeRule()} is what keeps EDT's own defaults OUT of the file in the first
 * place; a parsed file no longer carries that difference.)
 * <p>
 * <b>Round-trip is lossless by construction.</b> The model is a generic XML element tree, not
 * a projection onto the handful of things this plugin understands: {@code Properties} maps,
 * nested sections and any attribute or element added by a future EDT are held verbatim and
 * re-emitted unchanged. Rules are held as the STRING literal found in the file, so an unknown
 * future rule survives a rewrite too - only a rule this plugin is asked to AUTHOR is parsed
 * and validated, and that happens in the tool, which is also the only place that may name the
 * platform's rule enum.
 * <p>
 * <b>"By construction" means the one input this model could not hold is never admitted into
 * it:</b> a document that uses an XML namespace. A prefix has no place here - an element is held
 * under its LOCAL name and its attributes are keyed by local names - so a declaration could not
 * be written back at all, a prefixed element would come back stripped, and two attributes
 * differing only by their prefix would collapse onto one key, the second deleting the first.
 * Rather than admit such a file and quietly rewrite the very payload this promise is about,
 * {@link MergeRulesCodec} REFUSES it at the parse; the refusal there also records why this format
 * never legitimately carries one.
 */
public final class MergeRulesDocument
{
    /** Key of the node that carries a rule for the WHOLE configuration. */
    public static final String ROOT_KEY = "$$Root$$"; //$NON-NLS-1$

    /** Literal written in a top-object key for a side on which the object does not exist. */
    public static final String SIDE_ABSENT = "NONE"; //$NON-NLS-1$

    /** The only {@code Format_version} the platform's own deserializer accepts. */
    public static final String SUPPORTED_FORMAT_VERSION = "2.0"; //$NON-NLS-1$

    /** Root element of the file. */
    public static final String TAG_SETTINGS = "Settings"; //$NON-NLS-1$

    /** Element holding the node tree. */
    public static final String TAG_MERGE_SETTINGS = "MergeSettings"; //$NON-NLS-1$

    /** One node of the tree. */
    public static final String TAG_NODE = "Node"; //$NON-NLS-1$

    /** Attribute holding a node's key. */
    public static final String ATTR_KEY = "Key"; //$NON-NLS-1$

    /** Attribute holding a node's merge rule. */
    public static final String ATTR_MERGE_RULE = "MergeRule"; //$NON-NLS-1$

    /** Attribute holding a node's ordering side ({@code Main} / {@code Other} / {@code CommonAncestor}). */
    public static final String ATTR_ORDER_SIDE = "OrderSide"; //$NON-NLS-1$

    /** Attribute of {@link #TAG_SETTINGS} holding the format version. */
    public static final String ATTR_FORMAT_VERSION = "Format_version"; //$NON-NLS-1$

    /**
     * Deepest path this plugin will AUTHOR a rule at, counted from the root: {@code 0} = the
     * root itself, {@code 1} = a feature collection, {@code 2} = a top object.
     * <p>
     * The bound is not timidity, it is the addressing model: below a top object the platform
     * keys nodes by {@code getPositionAfterMerge()} - a number that moves when any other rule
     * changes - so a deeper key authored from outside a live comparison would be a decision
     * pointing at whatever happens to sit at that position later. Deeper nodes present in a
     * file are still read, reported and preserved on rewrite.
     */
    public static final int MAX_AUTHORABLE_DEPTH = 2;

    /** Separator between the three names of a top-object key. */
    private static final char KEY_SEPARATOR = ':';

    private final Element settings;

    private final List<Element> prolog;

    private final List<Element> epilog;

    private String sourceLabel;

    private MergeRulesDocument(Element settings, List<Element> prolog, List<Element> epilog)
    {
        this.settings = settings;
        this.prolog = prolog;
        this.epilog = epilog;
    }

    /**
     * Wraps an already-parsed {@code Settings} element that stands alone in its document.
     *
     * @param settings the root element, never {@code null}
     * @return the document
     */
    public static MergeRulesDocument of(Element settings)
    {
        return of(settings, List.of(), List.of());
    }

    /**
     * Wraps an already-parsed {@code Settings} element together with what stood BESIDE it in the
     * document - the comments and processing instructions before and after the root.
     * <p>
     * They are held on the document rather than on the root element because that is where they
     * are: XML puts them outside it, and an element cannot hold a sibling. Dropping them would be
     * the same silent loss the text node exists to prevent, one level up - a licence header or a
     * generator's note above the root is exactly the kind of payload a rewrite must carry.
     *
     * @param settings the root element, never {@code null}
     * @param prolog the nodes before the root, in document order
     * @param epilog the nodes after the root, in document order
     * @return the document
     */
    public static MergeRulesDocument of(Element settings, List<Element> prolog,
        List<Element> epilog)
    {
        return new MergeRulesDocument(settings, List.copyOf(prolog), List.copyOf(epilog));
    }

    /**
     * Creates an empty document: {@code Settings} with the supported format version and an
     * empty {@code MergeSettings} / {@code $$Root$$} skeleton.
     *
     * @return a new empty document
     */
    public static MergeRulesDocument empty()
    {
        Element settings = new Element(TAG_SETTINGS);
        settings.attribute(ATTR_FORMAT_VERSION, SUPPORTED_FORMAT_VERSION);
        Element mergeSettings = new Element(TAG_MERGE_SETTINGS);
        Element root = new Element(TAG_NODE);
        root.attribute(ATTR_KEY, ROOT_KEY);
        mergeSettings.children().add(root);
        settings.children().add(mergeSettings);
        return new MergeRulesDocument(settings, List.of(), List.of());
    }

    /**
     * The {@code Settings} root element, with every child in document order.
     *
     * @return the root element
     */
    public Element settings()
    {
        return settings;
    }

    /**
     * The comments and processing instructions that stood BEFORE the root element.
     *
     * @return the nodes, in document order, never {@code null}
     */
    public List<Element> prolog()
    {
        return prolog;
    }

    /**
     * The comments and processing instructions that stood AFTER the root element.
     *
     * @return the nodes, in document order, never {@code null}
     */
    public List<Element> epilog()
    {
        return epilog;
    }

    /**
     * The declared format version.
     *
     * @return the {@code Format_version} attribute, or {@code null} when absent
     */
    public String formatVersion()
    {
        return settings.attribute(ATTR_FORMAT_VERSION);
    }

    /**
     * Where this document was read from, for the report: a file path, or
     * {@code <zip>!<entry>} when it came out of a zip.
     *
     * @return the label, or {@code null} when the document was not read from a file
     */
    public String sourceLabel()
    {
        return sourceLabel;
    }

    /**
     * Records where this document was read from.
     *
     * @param label the source label
     */
    public void setSourceLabel(String label)
    {
        this.sourceLabel = label;
    }

    /**
     * The {@code MergeSettings} element, created (and appended) when the file has none.
     *
     * @return the element, never {@code null}
     */
    public Element mergeSettings()
    {
        Element existing = findContainer(settings);
        if (existing != null)
        {
            return existing;
        }
        Element created = new Element(TAG_MERGE_SETTINGS);
        settings.children().add(created);
        return created;
    }

    /**
     * The {@code $$Root$$} node, created when the file has none.
     *
     * @return the root node, never {@code null}
     */
    public Element root()
    {
        Element container = mergeSettings();
        Element rootNode = findRoot(container);
        if (rootNode == null)
        {
            rootNode = new Element(TAG_NODE);
            rootNode.attribute(ATTR_KEY, ROOT_KEY);
            container.children().add(rootNode);
        }
        return rootNode;
    }

    /**
     * Every decision the file carries AT AN ADDRESS, in document order. A decision is a node with
     * a {@link #ATTR_MERGE_RULE} attribute - the file being sparse, that is exactly the set of
     * choices somebody made - and it is reached the way every other read here reaches a node:
     * {@link #findContainer(Element)} for the container, {@link #findRoot(Element)} for the one
     * address that container exposes, then {@link #findNode(Element, String)} down the keys.
     * <p>
     * <b>A rule that lies OUTSIDE that tree is not returned at all.</b> It used to be: the walk
     * started at every {@code Node} in the container and recursed through {@code children()}
     * directly, so a rule on a {@code Node} sitting BESIDE the root came back as a decision at
     * depth 0 - the root's own level - and two such siblings came back as two decisions at ONE
     * address, a shape the codec's duplicate-key refusal deliberately does not judge because no
     * request can reach it. The same held under a keyless node, whose missing key contributed an
     * empty path segment that {@link #findNode(Element, String)} can match on nothing.
     * <p>
     * <b>Why nothing rather than a second shape.</b> Reporting such a rule under a made-up address
     * is the failure - {@code MergeRulesTool} prints that address as a level it is not, and
     * validates it against a comparison, while {@link #mergeRuleAt(List)} and
     * {@link #setMergeRule(List, String)} could never follow it. Reporting it under a truthful
     * address is impossible, because it has none. Returning it in a separately named shape was the
     * other option and is declined: every consumer of this list answers one question - what will
     * the platform apply - and for an unreachable rule the answer is "nothing", exactly as for a
     * rule that is not in the file. What does NOT change is that the file keeps it: a rewrite
     * carries the element through verbatim, as it carries any other payload this plugin does not
     * interpret.
     * <p>
     * <b>What this list omits, {@link #unreachableRuleCount()} counts - by subtraction, so the two
     * cannot disagree.</b> Together they partition every {@link #ATTR_MERGE_RULE} attribute in the
     * document, each one reported exactly once; see the invariant stated there.
     *
     * @return the decisions, never {@code null}
     */
    public List<Decision> decisions()
    {
        Element container = findContainer(settings);
        if (container == null)
        {
            // No container is no node tree, so there is no address a decision could sit at.
            return Collections.emptyList();
        }
        Element rootNode = findRoot(container);
        if (rootNode == null)
        {
            // The container exposes exactly one address and the file does not carry it: nothing
            // in it is reachable, so nothing in it is a decision at an address.
            return Collections.emptyList();
        }
        List<Decision> collected = new ArrayList<>();
        collect(rootNode, List.of(ROOT_KEY), collected);
        return collected;
    }

    /**
     * Number of blocks this plugin does not interpret but carries through a rewrite verbatim,
     * counted over the WHOLE document. Two kinds reach the count, and both are payload a
     * caller may have put there: a section beside the node tree ({@code Correspondences} is
     * the platform's own) and a section inside it ({@code Properties} maps, nested sections).
     * Only the {@code Node} tree and the ONE {@code MergeSettings} container
     * {@link #findContainer(Element)} picks are structure this plugin reads; everything else is
     * counted, one per block, without descending into it. Reported so a caller can see the
     * payload is still there.
     * <p>
     * <b>The container is recognised by IDENTITY, not by its tag</b>, and the difference is a
     * whole element: a document may carry a SECOND {@code <MergeSettings>}, which the codec
     * accepts because no lookup, decision or write here ever looks past the first. Keyed on the
     * tag, this count treated that second element as structure it reads - descending into it and
     * counting only the payload sections INSIDE it - so the element itself, which a rewrite
     * carries through exactly like a {@code Correspondences} block, was named nowhere. Keyed on
     * identity, everything this document does not read is a preserved block, and the file's
     * elements are partitioned by the same question every other reader here asks.
     * <p>
     * <b>This count and the rule counts measure DIFFERENT UNITS, and neither is a term of the
     * other's total.</b> This one counts BLOCKS a rewrite carries verbatim; {@link #decisions()}
     * and {@link #unreachableRuleCount()} count {@link #ATTR_MERGE_RULE} ATTRIBUTES, and those two
     * partition every such attribute in the document between them ({@link #ruleCount()}). So a
     * preserved block that happens to spell {@code MergeRule} inside itself is one block here AND
     * one unreachable rule there - two true facts about one element, not one fact counted twice.
     * Adding the two numbers is meaningless in either direction; each answers its own question.
     *
     * @return the count of preserved blocks
     */
    public int preservedSectionCount()
    {
        Element container = findContainer(settings);
        int count = 0;
        for (Element child : settings.children())
        {
            if (!child.isElement())
            {
                // Character data, a comment and a processing instruction are all preserved, and
                // none of them is a SECTION: they are the text of the element they sit in, and
                // counting them would report a "preserved block" a reader cannot find in the
                // file as a block.
                continue;
            }
            if (child == container)
            {
                count += countNonNodeElements(child);
            }
            else
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Number of {@link #ATTR_MERGE_RULE} attributes the document carries ANYWHERE - at an address
     * or not, on a {@code Node} or not, inside the container this document reads or outside it.
     * <p>
     * This is the WHOLE the other two rule counts partition, and it is deliberately the dumbest
     * question this class asks: walk every node of the document, prolog and epilog included, and
     * count the elements whose attribute map holds the name. Nothing about tags, containers, keys
     * or reachability enters it, because every one of those judgements is a chance for a shape
     * nobody anticipated to fall between two enumerations - which is exactly how this class came
     * to under-report a file four separate times.
     *
     * @return the count of merge-rule attributes in the document
     */
    public int ruleCount()
    {
        int count = 0;
        for (Element node : prolog)
        {
            count += rulesInside(node);
        }
        count += rulesInside(settings);
        for (Element node : epilog)
        {
            count += rulesInside(node);
        }
        return count;
    }

    /**
     * Number of merge rules the file carries where ADDRESSING CANNOT REACH THEM.
     *
     * <h2>The invariant this method exists to hold</h2>
     * <b>Every {@link #ATTR_MERGE_RULE} attribute in the document is reported exactly once:</b>
     * either {@link #decisions()} returns it under the address that reaches it, or this count
     * covers it. Never both, never neither -
     * {@code decisions().size() + unreachableRuleCount() == ruleCount()}, for every document,
     * always.
     * <p>
     * <b>It is DERIVED from that identity rather than enumerated, and that is the whole point.</b>
     * This count used to be a walk that visited the places a stray rule was known to hide - a
     * {@code Node} beside the root, a keyless node, a node shadowed by a same-keyed sibling, a
     * second {@code <MergeSettings>} - and each time a fifth place turned up, the file was
     * under-reported until a branch was added for it. Four such places were found one after
     * another, all the same shape of mistake: an enumeration walked STRUCTURE while claiming to
     * count a THING, so anything not on the list vanished from both halves of the report. Counting
     * the whole and subtracting the addressed half inverts the default: a shape nobody has thought
     * of yet is unreachable until addressing proves otherwise, so the worst a new shape can now do
     * is be reported as unaddressable - never as absent.
     * <p>
     * The subtraction cannot go negative, and not by luck: {@link #decisions()} reaches an element
     * only by descending {@code children()} from the root element, which is precisely the walk
     * {@link #ruleCount()} makes, and it visits each element at most once (it descends one
     * DISTINCT key at a time, and an element carries one key). So the decisions are a subset of
     * the whole.
     * <p>
     * <b>What "unreachable" claims, and what it does not.</b> It claims that key-chain resolution
     * - the container {@link #findContainer(Element)} picks, entered at {@link #findRoot(Element)}
     * and walked by {@link #findNode(Element, String)}, which is how EDT resolves a node too -
     * never arrives at the element carrying the attribute, so the rule applies to nothing and no
     * request here can address it. It does NOT claim to know what a foreign block MEANT by
     * spelling {@code MergeRule}: whatever that is, it is not a decision this format's own reader
     * will resolve, and saying so is the honest report either way.
     * <p>
     * <b>Counted because otherwise nothing mentions such a rule at all.</b> {@link #decisions()}
     * deliberately does not return one - it has no address to return it under - and
     * {@link #preservedSectionCount()} counts BLOCKS, not rules, so it cannot stand in for this.
     * Left unreported, a file whose only rules are unreachable reads as a file with no rule in it,
     * which is the same false claim of absence this model refuses to make anywhere else. What is
     * NOT claimed is that the file loses them: a rewrite carries the elements through verbatim, as
     * it carries any other payload this plugin does not interpret.
     *
     * @return the count of merge rules no address reaches
     */
    public int unreachableRuleCount()
    {
        return ruleCount() - decisions().size();
    }

    /**
     * The rule recorded at a path, if any.
     *
     * @param relativePath keys below {@link #ROOT_KEY} (empty addresses the root itself)
     * @return the rule literal exactly as written in the file
     */
    public Optional<String> mergeRuleAt(List<String> relativePath)
    {
        Element node = root();
        for (String key : relativePath)
        {
            node = findNode(node, key);
            if (node == null)
            {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(node.attribute(ATTR_MERGE_RULE));
    }

    /**
     * Records a decision, creating the intermediate nodes it needs. Everything already in the
     * document is kept: an existing node keeps its other attributes, its payload sections and
     * its children, and only its {@link #ATTR_MERGE_RULE} is set.
     *
     * @param relativePath keys below {@link #ROOT_KEY} (empty addresses the root itself)
     * @param ruleLiteral the rule literal to write, e.g. {@code GetFromOther}
     */
    public void setMergeRule(List<String> relativePath, String ruleLiteral)
    {
        Element node = root();
        for (String key : relativePath)
        {
            Element child = findNode(node, key);
            if (child == null)
            {
                child = new Element(TAG_NODE);
                child.attribute(ATTR_KEY, key);
                node.children().add(child);
            }
            node = child;
        }
        node.attribute(ATTR_MERGE_RULE, ruleLiteral);
    }

    /**
     * Whether a key addresses a top object, i.e. carries the three names
     * {@code main:other:ancestor}.
     * <p>
     * <b>Two separators are the SHAPE, not the proof.</b> Counting them alone accepted
     * {@code A::A}, whose middle component is not the name of a side and not
     * {@link #SIDE_ABSENT} either - it is nothing. EDT keys its nodes by string equality, so such
     * a key matches no node in any comparison; a decision written under it is reported as recorded
     * and can never be applied, which is the one failure this whole slice is built to refuse. So
     * every component has to NAME something. {@link #SIDE_ABSENT} is a name in that sense - it is
     * how the platform spells "the object does not exist on this side" - while an empty or
     * whitespace-only component is not.
     *
     * <p>
     * <b>It does NOT ask whether the object exists on any side</b> - see
     * {@link #spellsSideAbsentOnEveryTopObjectKeySide(String)}, which reports only how the key is
     * SPELLED. Existence is not a question a key can answer: {@code NONE} is both the platform's
     * absence marker and a legal 1C name, so the two readings of {@code NONE:NONE:NONE} are told
     * apart by a live comparison and by nothing else.
     *
     * @param key a node key
     * @return {@code true} when the key has exactly two separators AND all three components name
     *         something
     */
    public static boolean isTopObjectKey(String key)
    {
        return hasTopObjectKeyShape(key) && emptyTopObjectKeySides(key).isEmpty();
    }

    /**
     * Whether a top-object-shaped key SPELLS {@link #SIDE_ABSENT} on all three sides.
     * <p>
     * It reports the spelling and stops there, and the name says so, because the readings of that
     * spelling have opposite consequences and this predicate cannot choose between them (see
     * {@link TopObjectKey}). <b>The ambiguity is PER COMPONENT</b>, so a key of three such
     * components has eight readings, not two: each side is independently either absent or holding
     * an object named {@code NONE}. Seven of them describe a node a comparison really has - one
     * side is enough - and only the eighth, every side truly absent, describes nothing, since a
     * node is what one of its three sides contributed and a decision under such a key would be
     * reported as recorded and never applied.
     * <p>
     * The predicate used to be called {@code absentOnEveryTopObjectKeySide} and its caller refused
     * the key outright, stating that eighth reading as fact. Only a comparison can tell them
     * apart, so what this answers is the spelling and the decision belongs where the comparison
     * is.
     *
     * @param key a node key
     * @return {@code true} when the key has the top-object shape and each of its three components
     *         is exactly {@link #SIDE_ABSENT}
     */
    public static boolean spellsSideAbsentOnEveryTopObjectKeySide(String key)
    {
        if (!hasTopObjectKeyShape(key))
        {
            return false;
        }
        int first = key.indexOf(KEY_SEPARATOR);
        int second = key.indexOf(KEY_SEPARATOR, first + 1);
        // Exact equality, as TopObjectKey.parse reads the same literal: the platform writes NONE
        // and matches it verbatim, so an object genuinely named "none" is a name like any other.
        return SIDE_ABSENT.equals(key.substring(0, first))
            && SIDE_ABSENT.equals(key.substring(first + 1, second))
            && SIDE_ABSENT.equals(key.substring(second + 1));
    }

    /**
     * Whether a key is SHAPED like a top-object key - three colon-separated components - whether
     * or not each of them names something.
     * <p>
     * Separate from {@link #isTopObjectKey(String)} because the two answer different questions,
     * and a caller that refuses a malformed key needs the first: a key with two separators and an
     * empty component is a top-object key the caller MEANT, spelled wrongly, and telling them so
     * is worth more than treating it as some other kind of key.
     *
     * @param key a node key
     * @return {@code true} when the key has exactly two separators
     */
    public static boolean hasTopObjectKeyShape(String key)
    {
        return key != null && countSeparators(key) == 2;
    }

    /**
     * Which sides of a top-object-shaped key name nothing.
     *
     * @param key a key that {@link #hasTopObjectKeyShape(String)} accepts; anything else answers
     *            empty, because a key that is not that shape has no sides to report on
     * @return the side names ({@code main} / {@code other} / {@code ancestor}) whose component is
     *         empty or whitespace only, in that order; empty when every component names something
     */
    public static List<String> emptyTopObjectKeySides(String key)
    {
        if (!hasTopObjectKeyShape(key))
        {
            return Collections.emptyList();
        }
        int first = key.indexOf(KEY_SEPARATOR);
        int second = key.indexOf(KEY_SEPARATOR, first + 1);
        List<String> empty = new ArrayList<>();
        if (key.substring(0, first).isBlank())
        {
            empty.add("main"); //$NON-NLS-1$
        }
        if (key.substring(first + 1, second).isBlank())
        {
            empty.add("other"); //$NON-NLS-1$
        }
        if (key.substring(second + 1).isBlank())
        {
            empty.add("ancestor"); //$NON-NLS-1$
        }
        return empty;
    }

    /**
     * Whether a key is an engine-computed POSITION ({@code getPositionAfterMerge()}) rather
     * than a name. Such a key shifts when other rules change, so it is read-only for us.
     *
     * @param key a node key
     * @return {@code true} when the key is a bare non-negative integer
     */
    public static boolean isPositionKey(String key)
    {
        if (key == null || key.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < key.length(); i++)
        {
            if (key.charAt(i) < '0' || key.charAt(i) > '9')
            {
                return false;
            }
        }
        return true;
    }

    private static int countSeparators(String key)
    {
        int count = 0;
        for (int i = 0; i < key.length(); i++)
        {
            if (key.charAt(i) == KEY_SEPARATOR)
            {
                count++;
            }
        }
        return count;
    }

    // The four primitives below ARE the addressing, and they are package-scoped rather than
    // private because a second reader needs the same answers. MergeRulesCodec refuses a file whose
    // node tree says two things about one address, and a scan that answers "which element is the
    // container", "where does addressing enter it" or "which child does a key land on" in its OWN
    // way is a REPLICA of this class - one that drifted twice already, first judging a container
    // no lookup reads, then judging keyed nodes no lookup can reach. Each of those questions is
    // answered here, once, and the scan takes its answers from these methods; all it adds is the
    // one question addressing cannot ask, namely whether a pick had a SECOND candidate.

    /**
     * The {@code MergeSettings} element this document READS - the FIRST one, and the only one any
     * lookup, decision or write here ever touches.
     * <p>
     * Unlike {@link #mergeSettings()} it creates nothing, so a reader that must not change the
     * document asks the same question and gets the same element.
     *
     * @param settings the {@code Settings} root
     * @return the container, or {@code null} when the document has none
     */
    static Element findContainer(Element settings)
    {
        for (Element child : settings.children())
        {
            if (TAG_MERGE_SETTINGS.equals(child.tag()))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * The node addressing ENTERS a container at.
     * <p>
     * A container exposes exactly ONE address - {@link #ROOT_KEY} - and every path this document
     * reads or writes starts there ({@link #root()}, {@link #mergeRuleAt(List)},
     * {@link #setMergeRule(List, String)}). A {@code Node} that sits beside the root under its own
     * key is reachable by nothing.
     * <p>
     * Unlike {@link #root()} it creates nothing.
     *
     * @param container the {@code MergeSettings} element
     * @return the root node, or {@code null} when the container has none
     */
    static Element findRoot(Element container)
    {
        return findNode(container, ROOT_KEY);
    }

    /**
     * The {@code Node} children of an element, in document order - the list every lookup scans,
     * and therefore the definition of what counts as a node of the tree.
     * <p>
     * <b>It builds a NEW list on every call and scans every child to do it</b>, so how many times
     * it is asked about one element is that element's share of a walk's cost. It records that on
     * the element itself ({@link Element#nodeChildListings()}), which is how a walk that resolves
     * each key separately can be told apart from one that lists a level once - see
     * {@code MergeRulesCodec.rejectDuplicateSiblingKeys}, whose bound is pinned that way.
     *
     * @param parent the element to read
     * @return the node children, never {@code null}
     */
    static List<Element> nodeChildren(Element parent)
    {
        parent.nodeChildListings++;
        List<Element> nodes = new ArrayList<>();
        for (Element child : parent.children())
        {
            if (TAG_NODE.equals(child.tag()))
            {
                nodes.add(child);
            }
        }
        return nodes;
    }

    /**
     * The child a lookup for {@code key} LANDS ON: the FIRST {@code Node} child carrying it, which
     * is also how EDT itself resolves a node.
     *
     * @param parent the element to look in
     * @param key the key to match
     * @return the child, or {@code null} when no node carries the key
     */
    static Element findNode(Element parent, String key)
    {
        for (Element child : nodeChildren(parent))
        {
            if (key.equals(child.attribute(ATTR_KEY)))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * One level of the decision walk: the node's own rule, then the levels below it.
     * <p>
     * The descent goes through {@link #findNode(Element, String)} on each DISTINCT key rather than
     * through the child the loop happens to hold, so what is walked is by construction the element
     * a lookup for that key would land on - the same rule the codec's duplicate-key scan follows.
     * A child without a key is skipped entirely: {@code findNode} matches on tag AND key, so no
     * path can come to rest on it and nothing below it is reachable by any request either.
     *
     * @param node the node to read; its rule, if any, is a decision at {@code path}
     * @param path the key chain that addresses {@code node}, starting at {@link #ROOT_KEY}
     * @param collected the decisions found so far
     */
    private static void collect(Element node, List<String> path, List<Decision> collected)
    {
        String rule = node.attribute(ATTR_MERGE_RULE);
        if (rule != null)
        {
            collected.add(new Decision(path, rule, node.attribute(ATTR_ORDER_SIDE)));
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Element child : nodeChildren(node))
        {
            String key = child.attribute(ATTR_KEY);
            if (key != null)
            {
                keys.add(key);
            }
        }
        for (String key : keys)
        {
            List<String> here = new ArrayList<>(path);
            here.add(key);
            collect(findNode(node, key), here, collected);
        }
    }

    /**
     * Every {@link #ATTR_MERGE_RULE} attribute on a node and everything below it, whatever the
     * tags in between are.
     * <p>
     * The walk goes through {@code children()}, not through {@link #nodeChildren(Element)}: this
     * is the counter of the WHOLE, so it must not stop at a tag it does not recognise. A rule on a
     * {@code Properties} map, on a {@code Correspondences} entry, on a {@code <MergeSettings>}
     * element itself or on some element a future EDT adds is a spelling of {@code MergeRule} the
     * file carries, and the one thing the report must never do about it is fall silent.
     * <p>
     * Non-element nodes cost nothing to visit and are visited anyway - a text run, a comment and a
     * processing instruction hold no attributes and no children, so they contribute zero without a
     * kind test standing between this walk and a node it should have counted.
     *
     * @param node the subtree root
     * @return the count
     */
    private static int rulesInside(Element node)
    {
        int count = node.attribute(ATTR_MERGE_RULE) == null ? 0 : 1;
        for (Element child : node.children())
        {
            count += rulesInside(child);
        }
        return count;
    }

    private static int countNonNodeElements(Element element)
    {
        int count = 0;
        for (Element child : element.children())
        {
            if (!child.isElement())
            {
                // Same rule as preservedSectionCount: only an element is a block.
                continue;
            }
            if (TAG_NODE.equals(child.tag()))
            {
                count += countNonNodeElements(child);
            }
            else
            {
                count++;
            }
        }
        return count;
    }

    /**
     * A generic XML node: an element (tag, ordered attributes, ordered children), a run of
     * character data, a comment or a processing instruction. Kept deliberately dumb so that
     * anything the plugin does not understand still round-trips.
     * <p>
     * <b>Character data is a NODE in the child list, not a field beside it.</b> A single text
     * field per element cannot express mixed content - text before a child element and text after
     * it - and the shape that could not be expressed was silently mangled: the leading run was
     * dropped and the trailing one re-emitted BEFORE every child. A payload section this plugin
     * does not interpret is exactly where such content can appear, and preserving it verbatim is
     * the codec's whole promise, so text takes its place in {@link #children()} in document order.
     * <p>
     * <b>A comment and a processing instruction are nodes for the same reason.</b> They are the
     * one other thing a document can carry between two elements, they are payload this plugin
     * does not interpret, and a model that had no place for them dropped them on every rewrite -
     * the same silent loss the text node exists to prevent, on the content that most often
     * carries a human's note about WHY a decision was made. They hold their position among the
     * siblings, in document order, exactly as text does.
     */
    public static final class Element
    {
        /**
         * What a node IS. Kept as one field rather than derived from which other fields are set:
         * a comment and a text run both carry only text, so "no tag" stopped being an answer as
         * soon as there was more than one kind of non-element node.
         * <p>
         * Private, and answered from outside through the four predicates below rather than by
         * handing the constant out: callers ask "is this an element?", never "which of the four
         * is it?", and an exposed enum would be surface nothing uses.
         */
        private enum Kind
        {
            /** An element: a tag, attributes and children. */
            ELEMENT,
            /** A run of character data. */
            TEXT,
            /** A comment, held without its {@code <!--} / {@code -->} delimiters. */
            COMMENT,
            /** A processing instruction: a target and the data after it. */
            PROCESSING_INSTRUCTION
        }

        private final Kind kind;

        private final String tag;

        private final String textValue;

        private final String target;

        private final Map<String, String> attributes = new LinkedHashMap<>();

        private final List<Element> children = new ArrayList<>();

        /**
         * How many times this element's {@code Node} children have been LISTED.
         * <p>
         * A measurement and nothing else: it is written by {@link MergeRulesDocument#nodeChildren}
         * alone, no production code reads it, and it takes no part in what this element IS - it is
         * excluded from every rewrite, and two elements that differ only in it are the same
         * document. It exists because the cost of a tree walk is otherwise unobservable from
         * outside: a walk that lists a level once and one that re-resolves every key produce the
         * SAME result and differ only in how often they ask this question, so a test with no way
         * to count the asking can only pin the cost as a duration, which is not a bound.
         * <p>
         * It does not saturate: it is a plain {@code int}, so an element listed more than two
         * billion times would wrap. Nothing depends on the value, which is why that is acceptable
         * rather than guarded.
         */
        private int nodeChildListings;

        /**
         * Creates an element.
         *
         * @param tag the tag name
         */
        public Element(String tag)
        {
            this(Kind.ELEMENT, tag, null, null);
        }

        private Element(Kind kind, String tag, String textValue, String target)
        {
            this.kind = kind;
            this.tag = tag;
            this.textValue = textValue;
            this.target = target;
        }

        /**
         * Creates a text node - a run of character data holding its place among the siblings.
         *
         * @param value the character data, never {@code null}
         * @return the node
         */
        public static Element text(String value)
        {
            return new Element(Kind.TEXT, null, value == null ? "" : value, null); //$NON-NLS-1$
        }

        /**
         * Creates a comment node.
         *
         * @param value the comment body WITHOUT its delimiters, exactly as the parser reported it
         * @return the node
         */
        public static Element comment(String value)
        {
            return new Element(Kind.COMMENT, null, value == null ? "" : value, null); //$NON-NLS-1$
        }

        /**
         * Creates a processing-instruction node.
         *
         * @param target the instruction's target
         * @param data the data after the target, empty when the instruction carries none
         * @return the node
         */
        public static Element processingInstruction(String target, String data)
        {
            return new Element(Kind.PROCESSING_INSTRUCTION, null, data == null ? "" : data, //$NON-NLS-1$
                target);
        }

        /**
         * Whether this node is an element - the only kind that has a tag, attributes and children.
         *
         * @return {@code true} for an element
         */
        public boolean isElement()
        {
            return kind == Kind.ELEMENT;
        }

        /**
         * Whether this node is character data rather than an element. A text node has no tag, no
         * attributes and no children.
         *
         * @return {@code true} for a text node
         */
        public boolean isText()
        {
            return kind == Kind.TEXT;
        }

        /**
         * Whether this node is a comment.
         *
         * @return {@code true} for a comment
         */
        public boolean isComment()
        {
            return kind == Kind.COMMENT;
        }

        /**
         * Whether this node is a processing instruction.
         *
         * @return {@code true} for a processing instruction
         */
        public boolean isProcessingInstruction()
        {
            return kind == Kind.PROCESSING_INSTRUCTION;
        }

        /**
         * The character data of a text node, the body of a comment, or the data of a processing
         * instruction.
         *
         * @return the text, or {@code null} when this node is an element
         */
        public String textValue()
        {
            return textValue;
        }

        /**
         * The target of a processing instruction.
         *
         * @return the target, or {@code null} for any other kind of node
         */
        public String target()
        {
            return target;
        }

        /**
         * The tag name.
         *
         * @return the tag, or {@code null} for anything that is not an element
         */
        public String tag()
        {
            return tag;
        }

        /**
         * The attributes, in the order they were read or added. Live map: writing through it
         * is how the codec preserves an unknown attribute's position.
         *
         * @return the attribute map
         */
        public Map<String, String> attributes()
        {
            return attributes;
        }

        /**
         * Reads one attribute.
         *
         * @param name the attribute name
         * @return the value, or {@code null} when absent
         */
        public String attribute(String name)
        {
            return attributes.get(name);
        }

        /**
         * Sets one attribute. An attribute that already exists keeps its POSITION (re-putting
         * a key into a {@code LinkedHashMap} does not move it), so rewriting a rule does not
         * reshuffle a node this plugin did not author.
         *
         * @param name the attribute name
         * @param value the value
         * @return this element
         */
        public Element attribute(String name, String value)
        {
            attributes.put(name, value);
            return this;
        }

        /**
         * The child nodes, in document order - child elements and text runs alike. Live list.
         *
         * @return the children
         */
        public List<Element> children()
        {
            return children;
        }

        /**
         * How many times {@link MergeRulesDocument#nodeChildren} has listed this element's node
         * children - see {@link #nodeChildListings} for why the number exists at all.
         *
         * @return the count, never negative for any document this codec can read
         */
        int nodeChildListings()
        {
            return nodeChildListings;
        }
    }

    /**
     * One recorded merge decision: the node it applies to, addressed by its FULL key chain,
     * and the rule literal as the file spells it.
     * <p>
     * The chain is the address, never the last key on its own: sibling members under
     * different owners share a last segment, so a key alone does not identify a node.
     */
    public static final class Decision
    {
        private final List<String> path;

        private final String rule;

        private final String orderSide;

        Decision(List<String> path, String rule, String orderSide)
        {
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.rule = rule;
            this.orderSide = orderSide;
        }

        /**
         * The full key chain, starting at {@link MergeRulesDocument#ROOT_KEY}.
         *
         * @return the chain, never empty
         */
        public List<String> path()
        {
            return path;
        }

        /**
         * The rule literal exactly as written in the file.
         *
         * @return the rule literal
         */
        public String rule()
        {
            return rule;
        }

        /**
         * The ordering side, when the node carries one.
         *
         * @return {@code Main} / {@code Other} / {@code CommonAncestor}, or {@code null}
         */
        public String orderSide()
        {
            return orderSide;
        }

        /**
         * Depth below the root: {@code 0} is the root itself, {@code 1} a feature collection,
         * {@code 2} a top object.
         *
         * @return the depth
         */
        public int depth()
        {
            return path.size() - 1;
        }

        /**
         * The last key of the chain - the node's own key.
         *
         * @return the key
         */
        public String key()
        {
            return path.get(path.size() - 1);
        }

        /**
         * The three names, when this decision addresses a top object.
         *
         * @return the parsed key, or empty when the key is not a three-name key
         */
        public Optional<TopObjectKey> topObjectKey()
        {
            return isTopObjectKey(key()) ? Optional.of(TopObjectKey.parse(key())) : Optional.empty();
        }
    }

    /**
     * A top-object key split into the three components the platform joins with a colon.
     *
     * <h2>{@link MergeRulesDocument#SIDE_ABSENT} is AMBIGUOUS, and this class says so</h2>
     * The platform writes the literal {@code NONE} for a side that has no such object. It is also
     * a legal 1C name: the platform's own predicate for an identifier
     * ({@code com._1c.g5.v8.dt.common.StringUtils.isValidName}, read off its bytecode) asks only
     * that the first code point be alphabetic or {@code '_'} and the rest alphabetic, a digit or
     * {@code '_'} - there is no keyword list - so a metadata object may be called {@code NONE},
     * and {@code TopNodePathGenerator}, which formats {@code "%s:%s:%s"} out of the three
     * symlinks, then produces a component indistinguishable from the absence marker.
     * <p>
     * So a component that reads {@code NONE} establishes NEITHER answer on its own, and this class
     * hands out the spelling plus {@link SideState} rather than converting it to a decided one. It
     * used to answer {@code null} - "absent" - unconditionally, and every reader downstream then
     * stated an absence that had never been established: the read report printed "(absent)" for a
     * side that carries an object called {@code NONE}, and a key of three such components was
     * refused for naming no object at all, while a live comparison holds exactly that node and
     * resolves the key by string equality without any ambiguity to speak of.
     * <p>
     * Which is the general shape of the answer: <b>only a comparison can settle it</b>, because
     * only a comparison knows what the sides contain. Nothing here should try.
     */
    public static final class TopObjectKey
    {
        /** What one component of a top-object key establishes about its side. */
        public enum SideState
        {
            /**
             * The component names the object on that side. Any spelling other than
             * {@link MergeRulesDocument#SIDE_ABSENT}, which is what makes this one certain.
             */
            NAMED,
            /**
             * The component is the literal {@link MergeRulesDocument#SIDE_ABSENT}: the object is
             * absent on that side, OR the object is present and its name IS that literal. The key
             * alone cannot tell, and neither can this class.
             */
            AMBIGUOUS
        }

        private final String main;

        private final String other;

        private final String ancestor;

        private TopObjectKey(String main, String other, String ancestor)
        {
            this.main = main;
            this.other = other;
            this.ancestor = ancestor;
        }

        /**
         * Splits a three-component key, keeping every component EXACTLY as the file spells it.
         *
         * @param key a key with exactly two colon separators
         * @return the parsed key
         */
        public static TopObjectKey parse(String key)
        {
            int first = key.indexOf(KEY_SEPARATOR);
            int second = key.indexOf(KEY_SEPARATOR, first + 1);
            return new TopObjectKey(key.substring(0, first), key.substring(first + 1, second),
                key.substring(second + 1));
        }

        /**
         * Joins three names into a key, writing {@link MergeRulesDocument#SIDE_ABSENT} for an
         * absent side.
         * <p>
         * <b>The collision is the platform's and cannot be avoided here.</b> An absent side and a
         * side holding an object NAMED {@code NONE} produce the same component, because that is
         * the one key EDT matches against, and writing anything else would address no node at all.
         * What a caller can rely on is the reverse direction: {@link #parse} does not decide
         * between the two readings, and {@link #state} reports the component as
         * {@link SideState#AMBIGUOUS}.
         *
         * @param main the name on the main side, or {@code null} when the object is absent there
         * @param other the name on the other side, or {@code null} when the object is absent there
         * @param ancestor the name on the common-ancestor side, or {@code null} when the object is
         *            absent there
         * @return the key
         */
        public static String format(String main, String other, String ancestor)
        {
            return literal(main) + KEY_SEPARATOR + literal(other) + KEY_SEPARATOR + literal(ancestor);
        }

        private static String literal(String value)
        {
            return value == null ? SIDE_ABSENT : value;
        }

        /**
         * What a component establishes about its side.
         *
         * @param component one of the three components, as spelled
         * @return {@link SideState#AMBIGUOUS} for the {@link MergeRulesDocument#SIDE_ABSENT}
         *         literal, {@link SideState#NAMED} for anything else
         */
        public static SideState state(String component)
        {
            // Exact equality, as TopObjectKey.parse and EDT's own reader both match the literal
            // verbatim: an object genuinely named "none" is a name like any other.
            return SIDE_ABSENT.equals(component) ? SideState.AMBIGUOUS : SideState.NAMED;
        }

        /**
         * The main side's component, exactly as the file spells it.
         *
         * @return the component, never {@code null}; ask {@link #mainState()} what it establishes
         */
        public String main()
        {
            return main;
        }

        /**
         * The other side's component, exactly as the file spells it.
         *
         * @return the component, never {@code null}; ask {@link #otherState()} what it establishes
         */
        public String other()
        {
            return other;
        }

        /**
         * The common-ancestor side's component, exactly as the file spells it.
         *
         * @return the component, never {@code null}; ask {@link #ancestorState()} what it
         *         establishes
         */
        public String ancestor()
        {
            return ancestor;
        }

        /**
         * What the main component establishes.
         *
         * @return the state, never {@code null}
         */
        public SideState mainState()
        {
            return state(main);
        }

        /**
         * What the other component establishes.
         *
         * @return the state, never {@code null}
         */
        public SideState otherState()
        {
            return state(other);
        }

        /**
         * What the ancestor component establishes.
         *
         * @return the state, never {@code null}
         */
        public SideState ancestorState()
        {
            return state(ancestor);
        }

        // There is deliberately no isRename() here any more. It answered a BOOLEAN - "the object
        // was renamed" - over components that may be ambiguous, and the only way to keep the
        // boolean was to read an AMBIGUOUS component as an absence, which is the exact collapse
        // this class exists to stop: 'Added:NONE:Added' is a rename if that middle component is
        // the name NONE, and is not one if it is an absence, and the key does not say. Nothing in
        // this plugin called it, so the answer is to remove the question rather than to publish a
        // definite answer to it. A caller who needs it wants a THREE-valued one, taken with the
        // comparison in hand.
    }
}
