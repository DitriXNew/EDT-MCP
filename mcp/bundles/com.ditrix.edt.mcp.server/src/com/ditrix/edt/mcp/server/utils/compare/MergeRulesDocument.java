/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        for (Element child : settings.children())
        {
            if (TAG_MERGE_SETTINGS.equals(child.tag()))
            {
                return child;
            }
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
        Element rootNode = findNode(container, ROOT_KEY);
        if (rootNode == null)
        {
            rootNode = new Element(TAG_NODE);
            rootNode.attribute(ATTR_KEY, ROOT_KEY);
            container.children().add(rootNode);
        }
        return rootNode;
    }

    /**
     * Every decision the file carries, in document order. A decision is a node with a
     * {@link #ATTR_MERGE_RULE} attribute - the file being sparse, that is exactly the set of
     * choices somebody made.
     *
     * @return the decisions, never {@code null}
     */
    public List<Decision> decisions()
    {
        List<Decision> collected = new ArrayList<>();
        for (Element child : mergeSettings().children())
        {
            if (TAG_NODE.equals(child.tag()))
            {
                collect(child, new ArrayList<>(), collected);
            }
        }
        return collected;
    }

    /**
     * Number of blocks this plugin does not interpret but carries through a rewrite verbatim,
     * counted over the WHOLE document. Two kinds reach the count, and both are payload a
     * caller may have put there: a section beside the node tree ({@code Correspondences} is
     * the platform's own) and a section inside it ({@code Properties} maps, nested sections).
     * Only the {@code Node} tree and its {@code MergeSettings} container are structure this
     * plugin reads; everything else is counted, one per block, without descending into it.
     * Reported so a caller can see the payload is still there.
     *
     * @return the count of preserved blocks
     */
    public int preservedSectionCount()
    {
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
            if (TAG_MERGE_SETTINGS.equals(child.tag()))
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
     * @param key a node key
     * @return {@code true} when the key has exactly two separators AND all three components name
     *         something
     */
    public static boolean isTopObjectKey(String key)
    {
        return hasTopObjectKeyShape(key) && emptyTopObjectKeySides(key).isEmpty();
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

    private static Element findNode(Element parent, String key)
    {
        for (Element child : parent.children())
        {
            if (TAG_NODE.equals(child.tag()) && key.equals(child.attribute(ATTR_KEY)))
            {
                return child;
            }
        }
        return null;
    }

    private static void collect(Element node, List<String> path, List<Decision> collected)
    {
        String key = node.attribute(ATTR_KEY);
        List<String> here = new ArrayList<>(path);
        here.add(key == null ? "" : key); //$NON-NLS-1$
        String rule = node.attribute(ATTR_MERGE_RULE);
        if (rule != null)
        {
            collected.add(new Decision(here, rule, node.attribute(ATTR_ORDER_SIDE)));
        }
        for (Element child : node.children())
        {
            if (TAG_NODE.equals(child.tag()))
            {
                collect(child, here, collected);
            }
        }
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
     * A top-object key split into the three names the platform joins with a colon. The literal
     * {@link MergeRulesDocument#SIDE_ABSENT} means the side has no such object, which is a
     * fact about the comparison - not a name - and is therefore modelled as {@code null}.
     */
    public static final class TopObjectKey
    {
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
         * Splits a three-name key.
         *
         * @param key a key with exactly two colon separators
         * @return the parsed key
         */
        public static TopObjectKey parse(String key)
        {
            int first = key.indexOf(KEY_SEPARATOR);
            int second = key.indexOf(KEY_SEPARATOR, first + 1);
            return new TopObjectKey(side(key.substring(0, first)), side(key.substring(first + 1, second)),
                side(key.substring(second + 1)));
        }

        /**
         * Joins three names into a key, writing {@link MergeRulesDocument#SIDE_ABSENT} for an
         * absent side.
         *
         * @param main the name on the main side, or {@code null}
         * @param other the name on the other side, or {@code null}
         * @param ancestor the name on the common-ancestor side, or {@code null}
         * @return the key
         */
        public static String format(String main, String other, String ancestor)
        {
            return literal(main) + KEY_SEPARATOR + literal(other) + KEY_SEPARATOR + literal(ancestor);
        }

        private static String side(String value)
        {
            return SIDE_ABSENT.equals(value) ? null : value;
        }

        private static String literal(String value)
        {
            return value == null ? SIDE_ABSENT : value;
        }

        /**
         * The name on the main side.
         *
         * @return the name, or {@code null} when the object is absent there
         */
        public String main()
        {
            return main;
        }

        /**
         * The name on the other side.
         *
         * @return the name, or {@code null} when the object is absent there
         */
        public String other()
        {
            return other;
        }

        /**
         * The name on the common-ancestor side.
         *
         * @return the name, or {@code null} when the object is absent there
         */
        public String ancestor()
        {
            return ancestor;
        }

        /**
         * Whether the names differ between the sides, i.e. the object was renamed. Compares
         * only the sides that HAVE a name: an absent side is not a different name.
         *
         * @return {@code true} when two present names differ
         */
        public boolean isRename()
        {
            return !equalOrAbsent(main, other) || !equalOrAbsent(main, ancestor)
                || !equalOrAbsent(other, ancestor);
        }

        private static boolean equalOrAbsent(String left, String right)
        {
            return left == null || right == null || left.equals(right);
        }
    }
}
