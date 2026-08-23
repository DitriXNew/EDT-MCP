/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Element;

/**
 * Reads and writes EDT's merge-settings file into / out of {@link MergeRulesDocument}.
 * <p>
 * <b>WHICH CONTAINER THE PLATFORM READS DEPENDS ON THE EDT VERSION, and only the zip is read by
 * both.</b> Measured from the bytecode of {@code ComparisonManager.deserializeMergeSettings}: in
 * {@code com._1c.g5.v8.dt.compare} 28.0.1 (EDT 2026.1.2) it opens with the assertion
 * {@code May read merge settings from a xml-file or zip-file} and then branches on the extension;
 * in 29.0.0 (EDT 2026.2.0) the xml branch is GONE - the method opens with
 * {@code Assert.isTrue("zip".equals(FileUtil.getExtension(path)), "Can read merge settings from a zip file")}
 * and the whole class carries no {@code .xml} literal any more. So a {@code .xml} rules file is
 * read by 2026.1 and refused outright by 2026.2, while a {@code .zip} is read by both. READING
 * here accepts either, because either is a file somebody's EDT wrote.
 * <p>
 * <b>Writing a zip needs the comparison's own id, and this codec never invents one.</b> The
 * platform picks the entry whose name (minus its extension) equals
 * {@code <mainProject>_<otherProject>_<ancestorProject>} and merely LOGS A WARNING when no entry
 * matches - a zip whose entry is named anything else is silently ignored by EDT, decisions and
 * all. {@link #writeZip} therefore takes that id as an ARGUMENT: it is knowable only from a live
 * comparison ({@code ComparisonEngine.mergeRulesEntryId}), so a caller that cannot supply it has
 * to write {@code .xml} or refuse - never guess. {@link #write} writes the bare xml document.
 * <p>
 * <b>Writing is canonical and total.</b> The serializer emits every element, attribute and text
 * value the document holds, in the order it holds them, with a fixed layout (UTF-8, LF, two-space
 * indent). Nothing is projected away, so a file written from a parsed file differs from it only
 * in LAYOUT whitespace - and for a file already in this layout, not at all.
 * <p>
 * <b>Layout whitespace and character data are different things, and the line between them is
 * drawn per element:</b> an element whose character data is entirely whitespace is laid out, so
 * that whitespace is regenerated; an element with any non-whitespace character data is mixed
 * content, and then all of its character data - the spaces beside a child element included - is
 * kept byte for byte and written back inline. See
 * {@code separateLayoutFromContent}, which also states the one case the rule cannot tell apart.
 * <p>
 * <b>A comment and a processing instruction are payload too.</b> Both are kept as nodes in
 * document order, beside the text and the child elements, and re-emitted verbatim - a comment is
 * where a human writes down WHY a decision was made, and a rewrite that dropped it would delete
 * exactly the part of the file this codec has no other way to carry. They are kept in the prolog
 * and the epilog as well, where XML puts them outside the root element.
 * <p>
 * Two differences are known and stated rather than implied, because both are invisible to any XML
 * reader: an element with empty content is re-emitted self-closing ({@code <A></A>} becomes
 * {@code <A/>} - the same empty content); and the whitespace between a processing instruction's
 * target and its data is re-emitted as ONE space, because a parser reports the data with that
 * separator already consumed and how many spaces the file spelled is not knowable from what was
 * read.
 * <p>
 * <b>A document that uses an XML namespace is REFUSED, not read.</b> Namespace prefixes are not
 * modelled, and for this format that is not a gap: EDT's own serializer never writes one - it
 * makes no {@code writeNamespace} / {@code setPrefix} call anywhere - and its reader keys on
 * local names, so a prefix can only come from a hand edit, a block pasted in from somewhere else,
 * or a future EDT. What such a file cannot be is CARRIED, and that is why it is refused instead
 * of being read with a stated difference: a namespace declaration is not an attribute, so it is
 * never seen and can never be written back; a prefixed element is read under its local name and
 * comes back stripped; and two attributes that differ only by their prefix share one local name,
 * so the second overwrites the first and a value is deleted outright. Refusing a payload is
 * honest, silently mangling one is not - and the lossless promise on {@link MergeRulesDocument}
 * holds BECAUSE of this refusal. Modelling prefixes is a different job of a different size, and
 * no legitimate file of this format needs it. See {@code rejectNamespaceUse}.
 * <p>
 * <b>A document in which two SIBLING {@code <Node>} elements carry the same {@code Key} is
 * REFUSED too</b>, and for the same reason the namespace case is: it cannot be carried honestly.
 * EDT matches nodes by string equality, so of two siblings under one key only the first is ever
 * found - a rule written to that address updates the first and leaves the second in the file with
 * a rule of its own, and the rewrite then hands back a document that says two different things
 * about one node. The tool already refuses two decisions addressing one node in its own request;
 * this is that same question asked of the file. See {@code rejectDuplicateNodeKeys}.
 * <p>
 * <b>A document that declares an XML version this codec does not write is REFUSED as well</b>, and
 * it is the third member of the same family: what cannot be carried honestly goes back rather than
 * being rewritten into something else. The declaration this class emits is fixed
 * ({@code version="1.0"}), so a source that declared {@code 1.1} would come back declared
 * {@code 1.0} - and that is not a layout difference, it is a different GRAMMAR handed to the next
 * reader. XML 1.1 admits characters 1.0 has no spelling for: measured on this JDK's StAX reader,
 * {@code &#x1;} in text or in an attribute value parses under a {@code 1.1} declaration and yields
 * U+0001, while the same reference under {@code 1.0} is refused outright as "an invalid XML
 * character". Re-emitting that character under a {@code 1.0} declaration therefore produces a file
 * that THIS codec's own {@link #read(Path)} refuses - the tool would report the rules as written
 * and then be unable to read them back, and so would EDT. See {@code rejectUnwritableXmlVersion}.
 */
public final class MergeRulesCodec
{
    /** Extension of the plain-xml form. */
    public static final String XML_EXTENSION = ".xml"; //$NON-NLS-1$

    /** Extension of the zipped form the comparison editor saves. */
    public static final String ZIP_EXTENSION = ".zip"; //$NON-NLS-1$

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"; //$NON-NLS-1$

    /**
     * The XML version {@link #XML_DECLARATION} spells, and so the only one this codec can hand
     * back. A source that declares anything else is refused rather than re-declared - see
     * {@link #rejectUnwritableXmlVersion(String)}.
     */
    private static final String WRITABLE_XML_VERSION = "1.0"; //$NON-NLS-1$

    private static final String INDENT = "  "; //$NON-NLS-1$

    private static final String NEW_LINE = "\n"; //$NON-NLS-1$

    /**
     * Largest number of bytes this codec reads out of ONE source - a file or a zip entry - before
     * it stops and refuses.
     * <p>
     * The number is a ceiling on damage, not a guess at a real file. A merge-settings file is
     * SPARSE - one {@code Node} line per decision somebody actually made, around a hundred bytes -
     * so even a configuration-wide set of tens of thousands of decisions stays in the low
     * megabytes; the files saved off real comparisons are orders of magnitude under this. What the
     * bound is for is the other direction: whatever is read is parsed by the JVM the workbench
     * itself runs in, into a tree several times the size of its own bytes, so a generated or
     * accidentally bloated source would otherwise exhaust EDT's heap on the way to being parsed,
     * and the IDE - not just this call - would go down.
     * <p>
     * <b>It applies to the plain file exactly as it applies to a zip entry.</b> A zip is the more
     * obvious hazard, because a small archive of compressible bytes expands without bound - but
     * the heap is spent by the PARSE, and a plain {@code .xml} reaches the same parser by the
     * shorter route. Bounding only the zip left the whole defence resting on the caller having
     * chosen the container that is checked.
     * <p>
     * Package-scoped rather than private so the tests can build a document AT the bound instead of
     * restating the number: a test carrying its own copy of it would keep passing if this one moved.
     */
    static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    /** Working buffer size for the bounded read. */
    private static final int READ_CHUNK_BYTES = 64 * 1024;

    /**
     * Deepest element nesting this codec reads.
     * <p>
     * The bound is on the READER because that is the one place that protects every walk of the
     * tree at once. Reading is iterative and would swallow any depth, but three of the walks over
     * what it produces are recursive - the serializer, {@code MergeRulesDocument.decisions()} and
     * its section count - so a deep document does not fail the parse, it fails LATER with a
     * {@code StackOverflowError}. That is an {@link Error}: it is not a malformed-format
     * condition, it cannot honestly be caught as one, and it would abort a rewrite half-way
     * through instead of refusing it. Making one walk iterative would leave the other two, so the
     * depth is refused where it enters.
     * <p>
     * The number is far above any real file and far below what the stack can walk. A
     * merge-settings document is FLAT: the platform's own tree is a root, a feature collection, a
     * top object and its positional children - single digits - and a payload section beside it
     * adds a handful more. Five hundred nested elements is two orders of magnitude past that,
     * while the recursive re-emit it allows is a few hundred frames of one small method, well
     * inside the smallest thread stack any JVM starts with.
     */
    private static final int MAX_ELEMENT_DEPTH = 500;

    /**
     * Largest number of symbolic links {@link #followSymbolicLink(Path)} walks through before it
     * refuses.
     * <p>
     * A ring is caught by identity and named as a ring; this bound is for the chain that never
     * repeats a path yet never ends either - a link whose destination is reached through another
     * link to a directory grows the path at every hop, so "no path seen twice" does not by itself
     * terminate. Forty is what Linux allows one path resolution ({@code SYMLOOP_MAX} is 8 in
     * POSIX), so a chain this codec refuses is one the operating system would refuse too.
     */
    private static final int MAX_SYMLINK_HOPS = 40;

    /**
     * Largest number of entries {@link #readZip(Path)} walks before it refuses the archive.
     * <p>
     * The third member of one family, and it is bounded for the same reason as the other two:
     * {@link #MAX_DOCUMENT_BYTES} bounds what one entry EXPANDS to and {@link #MAX_ELEMENT_DEPTH}
     * bounds what the parse builds out of it, but both of those are spent only once an entry has
     * been CHOSEN - and choosing one walks the whole directory first. So an archive with a huge
     * directory exhausts the workbench's heap in that walk, before a single byte is decompressed
     * and before either existing bound is consulted. A bound that starts after the enumeration is
     * no bound on the enumeration.
     * <p>
     * <b>What this bound saves, stated exactly.</b> {@code ZipFile} has already read the central
     * directory into memory by the time this loop starts, so the CEN itself is not what is being
     * prevented - the JDK charged for that on open, and nothing here can give it back. What the
     * loop adds on top is a materialised {@code ZipEntry} plus a retained name {@code String} per
     * entry, which is a multiple of the CEN rather than a fraction of it, and it is that multiple
     * this bound refuses to pay.
     * <p>
     * The number is far past any real archive. EDT's comparison window saves ONE entry per
     * comparison - {@code '<mainProject>_<otherProject>_<ancestorProject>.xml'} - and this codec
     * already refuses anything that does not resolve to exactly one candidate, so a legitimate
     * source is a single-entry zip. A thousand is three orders of magnitude past that, and the
     * names kept for it are tens of kilobytes rather than the gigabytes an unbounded walk allows.
     */
    private static final int MAX_ZIP_ENTRIES = 1024;

    /**
     * Largest number of entry names the "not exactly one entry" refusal lists.
     * <p>
     * The refusal exists so the caller can see WHAT the archive holds instead of what was
     * expected, and a handful of names does that. Listing all of them does not: the message is
     * built by joining every name, so an archive at {@link #MAX_ZIP_ENTRIES} would answer a
     * one-line question with a message tens of kilobytes long - the same unbounded accumulation
     * the bound above exists to prevent, moved from the walk into the answer. What is left out is
     * COUNTED rather than dropped silently, because a list that simply stops reads as the whole of
     * what is in there.
     */
    private static final int MAX_LISTED_ZIP_ENTRIES = 20;

    private MergeRulesCodec()
    {
        // Utility class
    }

    /**
     * Parses merge-settings XML held in memory.
     *
     * @param xml the document text
     * @return the parsed document
     * @throws MergeRulesFormatException when the text is not a {@code Format_version="2.0"}
     *             merge-settings document
     */
    public static MergeRulesDocument parse(String xml) throws MergeRulesFormatException
    {
        try
        {
            return parse(newSecureFactory().createXMLStreamReader(new StringReader(xml)));
        }
        catch (XMLStreamException e)
        {
            throw new MergeRulesFormatException(notXml(e), e);
        }
    }

    /**
     * Parses merge-settings XML from a stream. The stream's own encoding declaration is honoured.
     * <p>
     * <b>This entry point is NOT bounded by {@link #MAX_DOCUMENT_BYTES}</b>, and cannot be: a
     * stream has no length, and reading one to find out is the very thing the bound exists to
     * avoid. The bound is applied where a source is opened - {@link #read(Path)} for a file or a
     * zip entry - so a caller who reaches for this method owns the question of how much it is
     * handing over. Every caller inside this codec passes bytes it has already counted.
     *
     * @param in the stream, closed by the caller
     * @return the parsed document
     * @throws MergeRulesFormatException when the stream is not a {@code Format_version="2.0"}
     *             merge-settings document
     */
    public static MergeRulesDocument parse(InputStream in) throws MergeRulesFormatException
    {
        try
        {
            return parse(newSecureFactory().createXMLStreamReader(in));
        }
        catch (XMLStreamException e)
        {
            throw new MergeRulesFormatException(notXml(e), e);
        }
    }

    /**
     * Reads a merge-settings file, xml or zip. The returned document records where it came from
     * ({@link MergeRulesDocument#sourceLabel()}), naming the zip entry when there was one - a
     * report must never present "the file" when what was read is one entry out of several.
     *
     * @param file the file to read
     * @return the parsed document
     * @throws IOException when the file cannot be read
     * @throws MergeRulesFormatException when its content is not a merge-settings document, or a
     *             zip does not hold exactly one readable candidate entry
     */
    public static MergeRulesDocument read(Path file) throws IOException, MergeRulesFormatException
    {
        if (isZip(file))
        {
            return readZip(file);
        }
        byte[] content;
        try (InputStream in = Files.newInputStream(file))
        {
            // Read through the same bound as a zip entry, and measured the same way - on the bytes
            // that actually arrive. Files.size() would answer from the directory entry, which is a
            // claim about the file rather than a count of what is being handed to the parser: a
            // file that grows while it is read, a named pipe and a filesystem that reports a size
            // it does not have all defeat it, and the heap is spent by the bytes either way.
            content = readAtMost(in, MAX_DOCUMENT_BYTES);
        }
        if (content == null)
        {
            throw new MergeRulesFormatException(tooLarge("The file '" + file + "' runs", //$NON-NLS-1$ //$NON-NLS-2$
                "Check what it actually holds, and point this at the merge-settings document " //$NON-NLS-1$
                    + "itself.")); //$NON-NLS-1$
        }
        MergeRulesDocument document = parse(new ByteArrayInputStream(content));
        document.setSourceLabel(file.toString());
        return document;
    }

    /**
     * Renders a document as canonical merge-settings XML.
     *
     * @param document the document
     * @return the XML text, ending with a newline
     */
    public static String serialize(MergeRulesDocument document)
    {
        StringBuilder out = new StringBuilder(XML_DECLARATION).append(NEW_LINE);
        for (Element node : document.prolog())
        {
            writeNode(out, node, 0, false);
        }
        writeElement(out, document.settings(), 0, false);
        for (Element node : document.epilog())
        {
            writeNode(out, node, 0, false);
        }
        return out.toString();
    }

    /**
     * What the caller has already decided about a file that may be sitting on the target path.
     *
     * <h2>Why the decision is an argument and not a check here</h2>
     * Whether an existing rules file may be replaced is a question about the CALLER'S intent -
     * {@code merge_rules} allows a replacement only when {@code basedOn} names that same file - and
     * this codec cannot answer it. What it can do, and what this enum exists for, is make the
     * decision take effect ATOMICALLY. A caller that answered "there is nothing there" with
     * {@code Files.exists} and then handed the write a move with {@code REPLACE_EXISTING} answered
     * it in two steps, and two concurrent writes both passed the first step and both performed the
     * second: the loser's decisions were destroyed by a call that had promised to refuse exactly
     * that.
     *
     * @see #write(Path, MergeRulesDocument, Target)
     */
    public enum Target
    {
        /**
         * The path must be free, and the write CLAIMS it in the same step that tests it - the
         * create-if-absent the filesystem performs as one indivisible operation. A second writer
         * that gets there first makes this one fail with {@link FileAlreadyExistsException}
         * instead of overwriting it.
         */
        MUST_NOT_EXIST,
        /**
         * The caller has established that whatever is on the path may be replaced - for
         * {@code merge_rules}, that {@code basedOn} names the very file being written, so the
         * document being written already carries the decisions that file holds.
         */
        MAY_BE_REPLACED
    }

    /**
     * Writes a document as a bare UTF-8 xml file, creating the parent directories when needed.
     * <p>
     * <b>That container is read by EDT 2026.1 and refused by 2026.2</b>, which reads a zip alone;
     * {@link #writeZip} produces the one both read. Which to write is the caller's decision,
     * because only the caller knows whether the comparison the file is for can be named at all -
     * see this class's javadoc.
     * <p>
     * The bytes land in a sibling temporary file that is then moved over the target, so a
     * same-path rewrite (reading a file and writing it back) cannot leave a half-written file
     * where a complete one used to be.
     * <p>
     * That temporary is UNIQUE PER CALL, and the uniqueness is load-bearing rather than tidy. A
     * fixed {@code <target>.tmp} is shared by every write aimed at the same path, so two
     * concurrent {@code merge_rules} writes interleaved as write-write-move-move: the second
     * overwrote the first's bytes before either move ran, both moves succeeded, and BOTH calls
     * reported that the document they had just validated was the one on disk - while the file held
     * one set of rules and nobody could tell whose. A per-operation temporary makes the two writes
     * independent; the last move still wins, which is what "replace" means, but each call's move
     * now carries its OWN bytes.
     * <p>
     * <b>A symbolic link is FOLLOWED, never replaced.</b> A move replaces a directory ENTRY, not
     * the content of the file behind it, so moving over a link would delete the link, leave the
     * file it named untouched, and still report the rules as written - the write would land on a
     * brand-new file nobody asked for while the caller's real rules file kept its old content. The
     * target is therefore resolved first and the bytes land on the file the link names, which is
     * the same file identity the caller's own guard accepted.
     *
     * <p>
     * <b>The replacement carries the target's POSIX MODE where the store accepts it, and only
     * ATTEMPTS its group.</b> A move replaces the directory entry, so the file that ends up on the
     * path is the TEMPORARY's inode with the temporary's mode - and {@code Files.createTempFile}
     * creates one readable by its owner alone. Replacing a rules file a team shares would
     * therefore have narrowed it to whoever ran the write, silently, on every save. The mode of
     * whatever is on the path is read and applied to the temporary BEFORE the move, so where the
     * store accepts it the file lands wearing the target's mode instead of the temporary's; where
     * the store answers that it keeps no POSIX permissions, applying it is SKIPPED and the
     * temporary's own mode stands. The GROUP is a separate step whose every failure is swallowed -
     * see {@code inheritGroup} - so a file can land with its mode carried and its group not.
     * <b>There is no blanket promise here that the target's permissions survive</b>: one attribute
     * is carried where the store accepts it, the other is attempted, and what a file's permissions
     * are made of beyond those two - a Windows ACL, an access-control entry somebody set by hand -
     * is not carried at all.
     * <p>
     * A filesystem with no POSIX view - Windows - has no mode or group to carry and is left alone.
     * A path with nothing on it has no mode to inherit either, so such a file keeps the
     * temporary's own - which is the restrictive one, and the safe direction for a file this call
     * is creating. Under {@link Target#MUST_NOT_EXIST} there IS something on the path by then: the
     * reservation this method made with {@code Files.createFile}, so the finished file wears the
     * process default rather than the temporary's owner-only mode.
     * <p>
     * <b>DECLARED LIMITATION: the target's OWNER is NOT carried.</b> This method replaces the file
     * OBJECT, so what ends up on the path is the temporary, belonging to whichever account the
     * temporary was created for; where that differs from the account the target belonged to, the
     * owner CHANGES and nothing in the answer says so. Carrying it is not attempted and the write
     * is not refused over it either - see {@code inheritPermissions} for why both alternatives
     * were measured and rejected. Do not aim {@code filePath} at a file that belongs to another
     * account.
     * <p>
     * <b>The move itself always replaces</b>, and it has to: it moves the temporary onto the
     * target. What {@link Target#MUST_NOT_EXIST} adds is a RESERVATION taken before a single byte
     * is written - {@code Files.createFile}, the create-if-absent the filesystem performs as one
     * indivisible operation - so the file the move then replaces is this call's own reservation
     * and never somebody else's rules. A reservation that is not consumed is removed again - on
     * EVERY failure that can follow it, including the one that creates the scratch file - so a
     * failed write leaves the path as free as it found it. An empty file left there would be
     * worse than the failure it followed: the write reports an I/O error, and every later write
     * to that path then refuses it as occupied while it holds no rules at all.
     *
     * @param file the target file
     * @param document the document
     * @param target what the caller has established about a file already on the path
     * @throws FileAlreadyExistsException when {@code target} is {@link Target#MUST_NOT_EXIST} and
     *     something is already on the path
     * @throws IOException when the file cannot be written
     */
    public static void write(Path file, MergeRulesDocument document, Target target)
        throws IOException
    {
        write(file, document, target, null);
    }

    /**
     * Writes a document as the single-entry zip EDT restores ONE comparison's decisions from.
     * <p>
     * Everything about the write - the temporary, the reservation, the inherited permissions, the
     * atomic move - is {@link #write(Path, MergeRulesDocument, Target)}'s, and only the BYTES
     * differ. What is added is the ADDRESS: the entry is named {@code <entryId>.xml}, and EDT
     * restores from it only when {@code entryId} equals the launching comparison's own
     * {@code <mainProject>_<otherProject>_<ancestorProject>}. An entry named anything else is
     * skipped with a log warning and the launch proceeds with NO decisions, so this method exists
     * to be handed a measured id rather than to derive one - a wrong id produces a file that looks
     * written and does nothing.
     * <p>
     * The entry's EXTENSION is free ({@code removeExtension} strips it before the match) and is
     * {@code .xml} anyway, because that is what {@code ComparisonManager.serializeMergeSettings}
     * writes and there is no reason for this archive to look different from EDT's own.
     *
     * @param file the target file, normally named {@code .zip}
     * @param document the document
     * @param target what the caller has established about a file already on the path
     * @param entryId the comparison id the entry is named after, without an extension; never
     *     {@code null} or blank
     * @throws IllegalArgumentException when {@code entryId} is absent - a zip with a made-up entry
     *     name is the silent no-op this class exists to prevent, so it cannot be produced here
     * @throws FileAlreadyExistsException when {@code target} is {@link Target#MUST_NOT_EXIST} and
     *     something is already on the path
     * @throws IOException when the file cannot be written
     */
    public static void writeZip(Path file, MergeRulesDocument document, Target target, String entryId)
        throws IOException
    {
        if (entryId == null || entryId.isBlank())
        {
            throw new IllegalArgumentException(
                "A zipped merge-rules file is addressed by the entry name the comparison looks " //$NON-NLS-1$
                    + "for, and none was supplied. EDT ignores an archive whose entry is named " //$NON-NLS-1$
                    + "anything else, so no name can be invented here."); //$NON-NLS-1$
        }
        write(file, document, target, entryId);
    }

    /**
     * The shared write. {@code zipEntryId} picks the container: {@code null} writes the bare xml
     * document, a name writes a one-entry zip carrying it.
     *
     * @param file the target file
     * @param document the document
     * @param target what the caller has established about a file already on the path
     * @param zipEntryId the zip entry's name without its extension, or {@code null} for bare xml
     * @throws IOException when the file cannot be written
     */
    private static void write(Path file, MergeRulesDocument document, Target target, String zipEntryId)
        throws IOException
    {
        // FIRST, and before a single filesystem step - not even the parent directories. This is
        // the one refusal that has to leave the path exactly as it found it, and a check made
        // after the reservation or after the temporary would already have created something to
        // clean up. Nothing below this line can be reached by a document this codec could not
        // read back.
        byte[] body = bodyOf(document, zipEntryId, target);
        Path resolved = followSymbolicLink(file.toAbsolutePath());
        Path parent = resolved.getParent();
        if (parent == null)
        {
            throw new IOException("Cannot write merge rules to '" + file //$NON-NLS-1$
                + "': it names a filesystem root, not a file."); //$NON-NLS-1$
        }
        Files.createDirectories(parent);
        // Taken BEFORE the bytes are produced, so there is no window between "the path is free"
        // and "the path is mine". Files.createFile either claims the name or fails; it cannot
        // report a claim it did not make, which is the whole difference from an exists() check.
        boolean reserved = false;
        if (target == Target.MUST_NOT_EXIST)
        {
            Files.createFile(resolved);
            reserved = true;
        }
        // EVERY step that can fail after the reservation is inside this block, and that is the
        // point of the shape rather than a matter of taste: the reservation is an empty file on
        // the caller's path, so any exit that leaves it behind makes the NEXT write refuse a path
        // holding no rules. Creating the temporary is one of those steps - a filesystem out of
        // inodes, over quota, or a directory whose permissions changed between the two calls
        // fails it - so it is created here and not before.
        // The temporary lives in the CALLER's directory, so a failure that leaves it behind leaves
        // litter in a place the caller owns. Every failing exit removes it; the successful one does
        // not need to, because the move consumed it.
        Path temporary = null;
        try
        {
            // Created in the TARGET's directory, never in the system temp area: the move over the
            // target has to stay within one filesystem to be atomic. The name carries the target's
            // own name so a leftover is traceable to the write that left it.
            temporary = Files.createTempFile(parent, resolved.getFileName().toString() + '.',
                ".tmp"); //$NON-NLS-1$
            Files.write(temporary, body);
            // Before the move, never after: after it there is a window in which the file on the
            // caller's path is readable by its owner alone, and a reader that lost the race gets a
            // permission error on a file that is about to be perfectly readable.
            inheritPermissions(resolved, temporary);
            try
            {
                Files.move(temporary, resolved, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            if (temporary != null)
            {
                cleanUp(temporary, e);
            }
            if (reserved)
            {
                // The empty reservation is this call's own litter: the path was free when the call
                // started and nothing was written onto it, so leaving a zero-byte file behind
                // would make the next attempt refuse a path nobody is using.
                cleanUp(resolved, e);
            }
            throw e;
        }
    }

    /**
     * The bytes that land on disk: the serialized document, or that document inside the single zip
     * entry a comparison restores from.
     * <p>
     * Assembled in memory rather than streamed into the temporary, and that is not laziness: the
     * serializer already builds the whole text as one {@code String}, so the archive costs one
     * more copy of a document this class bounds at {@link #MAX_DOCUMENT_BYTES} on the way in -
     * against a partially written archive if the assembly threw half way through the file.
     *
     * <h2>The bound is enforced on the way OUT as well</h2>
     * {@link #MAX_DOCUMENT_BYTES} used to be a rule about sources this codec accepts, and nothing
     * checked what it produced. A document that arrives just under the bound and grows - one more
     * decision, or simply the canonical printing expanding a compact source - serialises past it,
     * lands on disk, and is reported as written; the next {@link #read(Path)} of that file, and
     * every same-path rewrite after it, then refuses this tool's OWN output as too large. So the
     * serialised bytes are measured here, against the same number and by the same count the
     * reader uses, and an oversized document is refused instead of written.
     * <p>
     * <b>For a zip it is the ENTRY that is measured, not the archive.</b> {@link #readZip(Path)}
     * bounds what the entry EXPANDS to, so these very bytes are what the next read will count -
     * and a merge-settings document is repetitive enough to compress by orders of magnitude, so
     * measuring the archive would wave through exactly the files that cannot be read back.
     *
     * @param document the document to write
     * @param zipEntryId the zip entry's name without its extension, or {@code null} for bare xml
     * @param target what the caller has established about a file already on the path, so the
     *     refusal can say what became of it
     * @return the file's content
     * @throws IOException when the archive cannot be assembled, or when the document serialises
     *     past {@link #MAX_DOCUMENT_BYTES} - in which case nothing has been written
     */
    private static byte[] bodyOf(MergeRulesDocument document, String zipEntryId, Target target)
        throws IOException
    {
        byte[] xml = serialize(document).getBytes(StandardCharsets.UTF_8);
        if (xml.length > MAX_DOCUMENT_BYTES)
        {
            throw new IOException(tooLargeToWrite(xml.length, zipEntryId, target));
        }
        if (zipEntryId == null)
        {
            return xml;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            // '<id>.xml', exactly as ComparisonManager.serializeMergeSettings names it. The reader
            // strips the extension before matching, so the extension itself is free - the STEM is
            // the address, and it is the caller's, not this method's.
            zip.putNextEntry(new ZipEntry(zipEntryId + XML_EXTENSION));
            zip.write(xml);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Carries the POSIX mode of whatever is on {@code target} onto the {@code replacement} that
     * is about to take its place, and ATTEMPTS its owning group.
     * <p>
     * <b>Neither step is unconditional, so this method does not preserve "the target's
     * permissions".</b> The mode is applied only where the store accepts it - a
     * {@code setPosixFilePermissions} the filesystem answers as unsupported is skipped and the
     * temporary keeps its own mode - and every failure of the group step is swallowed. One
     * attribute is carried where it can be, the other is tried; nothing else about the file's
     * security is touched.
     * <p>
     * A move replaces an inode, so without this the replacement arrives wearing
     * {@code Files.createTempFile}'s own mode - owner-only - and the group of whoever ran the
     * write, and a merge-rules file a team shares becomes unreachable to everyone but that
     * account. Nothing about that is visible in the answer the caller gets: the write succeeds and
     * reports the rules as written.
     * <p>
     * <b>The group is ATTEMPTED for the same reason the mode is carried, and the attempt is
     * BEST-EFFORT.</b>
     * A file shared through a secondary group - {@code rw-rw-r--} owned by {@code developers} -
     * keeps its mode across the move and still stops being writable by the team, because the group
     * those {@code rw-} bits apply to is no longer the team's. Preserving the mode while dropping
     * the group preserves the half that does nothing on its own. It is still only ATTEMPTED, and
     * the guide says so rather than promising the group is kept: setting one needs the account to
     * belong to it, so where the filesystem says no the file lands with the group it was created
     * with.
     *
     * <h2>DECLARED LIMITATION: the user OWNER is not carried, and no write is refused over it</h2>
     * The file that ends up on the path is the temporary, so it belongs to whichever account the
     * temporary was created for; where that differs from the target's owner, the owner CHANGES -
     * silently, exactly the way the mode used to. Both ways of doing better were tried and are
     * worse:
     * <ul>
     * <li><b>Preserving it is not GUARANTEED by anything public.</b> {@code Files.getOwner} and
     * {@code Files.setOwner} are public and do exist, so what is missing is the guarantee rather
     * than the API. The replacing move goes through {@code MoveFileEx} on the platform this plugin
     * runs on, which brings the TEMPORARY's whole security descriptor onto the path; applying an
     * owner afterwards needs a privilege an ordinary account does not hold, and the ACL view cannot
     * represent a full descriptor. That same descriptor swap is why the target's ACL does not
     * survive either, even where the owner happens to match.</li>
     * <li><b>So "preserve it or refuse the write" is a refusal in ORDINARY cases</b> - a target
     * left owned by {@code BUILTIN\Administrators} by one elevated run, or by a colleague's
     * account on a share - which trades a quiet defect for a rules file that cannot be saved at
     * all. That is the more expensive of the two, so it was withdrawn.</li>
     * <li><b>Writing into the existing file instead of replacing it is worse again.</b> The right
     * to replace a file through its directory is not the right to open THAT file for writing, and
     * a failure after the truncate leaves a partial file on the path where a concurrent reader can
     * see it - which is precisely what the temporary-then-move exists to prevent.</li>
     * </ul>
     * The limitation is therefore declared - here, in {@code write}, and in the tool's guide - and
     * the advice that follows from it is the caller's to act on: do not aim a write at a file that
     * belongs to another account.
     * <p>
     * Three things are deliberately NOT done here.
     * <ul>
     * <li><b>Nothing is invented for a path that is empty.</b> A target that does not exist has no
     * mode or group to inherit, and Java cannot read the process umask, so guessing one would be
     * this method asserting a permission set nobody chose. Such a file keeps the temporary's own,
     * which is the restrictive direction. Note that a {@link Target#MUST_NOT_EXIST} write does NOT
     * reach this case: its reservation is already on the path, created with the process default,
     * and that is what gets carried.</li>
     * <li><b>A filesystem without POSIX permissions is not a failure.</b> Windows has no mode or
     * group to carry, and the view is simply absent there; treating that as an error would make
     * every write on Windows fail over a concept that does not exist on it. This method is then a
     * no-op from end to end, which is the limitation above stated in code.</li>
     * <li><b>A group that cannot be set is a SKIP, not a write failure.</b> Changing a group is
     * refused whenever the account does not belong to the target's group - a file left behind by a
     * colleague, a shared directory the writer is not a member of - and the filesystem may not
     * support the operation at all. Refusing the whole write over it would turn a rules file that
     * saves perfectly well today into one that cannot be saved, over a permission the caller never
     * asked this tool to manage; the mode is still carried, and the file still lands. That is why
     * the group is attempted AFTER the mode, and why the two swallow DIFFERENT amounts: the
     * group's failures are swallowed wholesale, while applying the mode swallows only the store's
     * {@code UnsupportedOperationException} - an {@link IOException} from applying it still fails
     * the write, before the move, with the target untouched.</li>
     * </ul>
     *
     * @param target the file about to be replaced
     * @param replacement the temporary that will replace it
     * @throws IOException when the mode was read and applying it failed with an I/O error - which
     *     happens before the move, so the target is still untouched and the caller's cleanup runs.
     *     A store that answers "no such concept" is NOT an I/O error and is skipped instead
     */
    private static void inheritPermissions(Path target, Path replacement) throws IOException
    {
        PosixFileAttributeView view =
            Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (view == null)
        {
            return;
        }
        PosixFileAttributes attributes;
        try
        {
            attributes = view.readAttributes();
        }
        catch (NoSuchFileException e) // NOSONAR nothing on the path is a state, not a failure
        {
            // Read rather than probed with exists(): one syscall answers both questions, and a
            // check followed by a read can be overtaken between the two.
            return;
        }
        catch (UnsupportedOperationException e) // NOSONAR the filesystem answered "no such concept"
        {
            // The view existed but the store does not really keep POSIX attributes.
            return;
        }
        try
        {
            Files.setPosixFilePermissions(replacement, attributes.permissions());
        }
        catch (UnsupportedOperationException e) // NOSONAR as above, on the writing side
        {
            // Nothing to carry onto: the temporary's own mode stands.
        }
        inheritGroup(replacement, attributes.group());
    }

    /**
     * Carries one group onto the replacement, and gives up quietly when it cannot.
     * <p>
     * Every way this can fail is a fact about the account and the filesystem rather than about the
     * write: the process may not belong to that group, the store may not implement the operation,
     * a security policy may forbid it. None of them is a reason to refuse to save the caller's
     * rules, so none of them is allowed to escape - the file lands with its mode carried and its
     * group left as created, which is the state the whole write had before this existed. Carrying
     * the group is BEST-EFFORT, and the guide says so rather than promising it is kept.
     *
     * @param replacement the temporary that will replace the target
     * @param group the target's group, or {@code null} when the attributes carried none
     */
    private static void inheritGroup(Path replacement, GroupPrincipal group)
    {
        if (group == null)
        {
            return;
        }
        PosixFileAttributeView view =
            Files.getFileAttributeView(replacement, PosixFileAttributeView.class);
        if (view == null)
        {
            return;
        }
        try
        {
            view.setGroup(group);
        }
        catch (IOException | UnsupportedOperationException | SecurityException e) // NOSONAR see above
        {
            // Not this write's business to fail over. See the javadoc above for why each of these
            // is an ordinary state of a shared filesystem rather than an error.
        }
    }

    /**
     * Removes one file this write created, attaching a removal failure to the failure on its way
     * out rather than replacing it.
     *
     * @param file the file to remove
     * @param failure the failure being reported
     */
    private static void cleanUp(Path file, Exception failure)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException suppressed)
        {
            failure.addSuppressed(suppressed);
        }
    }

    /**
     * The file the bytes must actually land on: the target itself, or - when the target is a
     * symbolic link - the file at the END of the chain of links it starts.
     * <p>
     * A DANGLING link is resolved through its own recorded destination rather than through the
     * filesystem, which cannot answer for a file that is not there: the write then creates the
     * file the link points at, which is what following the link means, instead of turning the link
     * into a regular file.
     * <p>
     * <b>The whole chain, one link at a time.</b> A link may name another link, and resolving only
     * the first hop puts the write on the INTERMEDIATE link - which the move then replaces with a
     * regular file, deleting a link nobody asked about and leaving the file at the end of the
     * chain with its old content, while the report says the rules were written. That is the very
     * defect following a link exists to prevent, one remove further along, so the walk continues
     * until it reaches something that is not a link. Each hop resolves a relative destination
     * against THAT hop's own directory, not the original file's.
     *
     * @param file an absolute path
     * @return the path to write, never {@code null}
     * @throws IOException when a link cannot be read, or the chain rings or runs past
     *             {@link #MAX_SYMLINK_HOPS}
     */
    private static Path followSymbolicLink(Path file) throws IOException
    {
        if (!Files.isSymbolicLink(file))
        {
            return file;
        }
        try
        {
            // Resolves the whole chain in one call - and canonicalises it - whenever every hop
            // exists. It cannot answer for a chain that ends in a missing file, or one that rings.
            return file.toRealPath();
        }
        catch (IOException e)
        {
            return walkDanglingChain(file);
        }
    }

    /**
     * Follows a chain of symbolic links by reading each link's recorded destination, stopping at
     * the first hop that is not a link.
     * <p>
     * A path already seen is a RING: it would be followed for ever, and there is no file at the
     * end of it to write, so it is refused rather than resolved. Identity is compared on the
     * NORMALISED path, which is what makes {@code a -> ./a} the same hop as {@code a}; the path
     * carried forward is the un-normalised one, because collapsing {@code ..} across a symlinked
     * directory would name a different file than the link does.
     *
     * @param start the link to start from, absolute
     * @return the first path in the chain that is not a symbolic link
     * @throws IOException when a link cannot be read, or the chain rings or runs too long
     */
    private static Path walkDanglingChain(Path start) throws IOException
    {
        return walkLinkChain(start,
            path -> Files.isSymbolicLink(path) ? Files.readSymbolicLink(path) : null);
    }

    /**
     * The chain walk itself, over any source of link destinations.
     * <p>
     * Package-private, and taking the reader as an argument, so that the ring and the hop bound
     * are provable WITHOUT a filesystem that grants symbolic links: creating one on Windows needs
     * a privilege the build does not have, and a test that is skipped proves nothing about the
     * loop it was written for.
     *
     * <h2>Declared limitation: a relative destination is resolved LEXICALLY</h2>
     * <b>What is not guaranteed.</b> For a DANGLING chain - the only chain that reaches this walk,
     * since a chain whose every hop exists is resolved by {@code Path.toRealPath()} one level up -
     * a hop whose recorded destination is RELATIVE is resolved against {@code current.getParent()}
     * and then {@code normalize()}d. Both steps are string operations. {@code getParent()} returns
     * the parent COMPONENT of the path as written, which is a name for the directory and not the
     * directory itself; {@code normalize()} then collapses {@code ..} before any filesystem lookup
     * happens. Where the containing directory is itself reached through a symbolic link, the
     * operating system would resolve the same destination against the directory the link POINTS
     * AT, and the two answers can name different files. The walk can therefore end on a path the
     * kernel would not have arrived at, and the rules are then written there.
     * <p>
     * <b>When it shows.</b> Only when all three hold at once: the chain is dangling (so
     * {@code toRealPath()} could not answer), a hop's destination is relative, and the directory
     * containing that hop is reached through a symbolic link to somewhere else - or the
     * destination climbs out of it with {@code ..}. The everyday cases are unaffected: an absolute
     * destination never consults the parent, a relative destination inside a real directory
     * resolves identically either way, and a chain that fully exists never gets here.
     * <p>
     * <b>Why it is declared rather than patched.</b> An honest answer requires resolving EVERY
     * containing directory through the filesystem - the walk would have to canonicalise the parent
     * at each hop before joining the destination, and fall back to the lexical form only for the
     * parts of the path that do not exist yet, which is a different algorithm from this loop
     * rather than a stricter version of it. Three rounds of review each removed one lexical
     * assumption from this walk (one link, then the whole chain, then this parent), and each fix
     * exposed the next one, so the family is closed here: the remaining case is named, bounded and
     * left to the caller, who can avoid it entirely by passing the path of the file itself.
     *
     * @param start the path to start from, absolute
     * @param links answers each hop's recorded destination, or {@code null} when the path is not
     *            a link
     * @return the first path in the chain that is not a link; for a relative destination this is
     *             the LEXICALLY resolved path, which can differ from the kernel's answer - see the
     *             declared limitation above
     * @throws IOException when a link cannot be read, or the chain rings or runs past
     *             {@link #MAX_SYMLINK_HOPS}
     */
    static Path walkLinkChain(Path start, LinkReader links) throws IOException
    {
        Set<Path> seen = new LinkedHashSet<>();
        seen.add(start.normalize());
        Path current = start;
        for (int hop = 0; hop < MAX_SYMLINK_HOPS; hop++)
        {
            Path destination = links.destinationOf(current);
            if (destination == null)
            {
                return current;
            }
            // LEXICAL on purpose, and its cost is stated in this method's javadoc: getParent() is
            // the parent component of the path as written, and normalize() collapses '..' before
            // the filesystem ever resolves a directory, so a relative destination under a
            // symlinked parent can name a file the kernel would not have chosen.
            Path base = current.getParent();
            Path next = destination.isAbsolute() || base == null ? destination.toAbsolutePath()
                : base.resolve(destination).toAbsolutePath().normalize();
            if (!seen.add(next.normalize()))
            {
                throw new IOException("Cannot write merge rules to '" + start //$NON-NLS-1$
                    + "': the symbolic links starting there form a ring (" //$NON-NLS-1$
                    + String.join(" -> ", seen.stream().map(Path::toString).toList()) //$NON-NLS-1$ //$NON-NLS-2$
                    + " -> " + next + "), so there is no file at the end of it to write. Repoint " //$NON-NLS-1$ //$NON-NLS-2$
                    + "one of those links at a real file, or name that file directly."); //$NON-NLS-1$
            }
            current = next;
        }
        throw new IOException("Cannot write merge rules to '" + start //$NON-NLS-1$
            + "': it starts a chain of more than " + MAX_SYMLINK_HOPS //$NON-NLS-1$
            + " symbolic links, which is more than the operating system itself resolves. Name the " //$NON-NLS-1$
            + "file you mean directly."); //$NON-NLS-1$
    }

    /**
     * Whether a path names the zipped form, for THIS codec's own reading.
     * <p>
     * Case-INSENSITIVE, and deliberately not the same rule as
     * {@link #hasReadableExtension(Path)}. This one decides how to open a file somebody already
     * has; that one decides whether the PLATFORM will accept the name, and the platform compares
     * the extension with {@code String.equals}. Reading {@code RULES.ZIP} costs nothing and helps
     * a caller who renamed a file; writing one would produce an archive EDT never opens.
     *
     * @param file the path
     * @return {@code true} when the file name ends with {@code .zip}, case-insensitively
     */
    public static boolean isZip(Path file)
    {
        String name = lowerCaseFileName(file);
        return name != null && name.endsWith(ZIP_EXTENSION);
    }

    /**
     * Whether a path's NAME carries an extension SOME supported EDT's merge-settings reader
     * accepts - the wider of the two VERSION rules, and the exact one on spelling.
     * <p>
     * The rule is the platform's, not ours, and it is a rule about the NAME. It is
     * VERSION-DEPENDENT: {@code deserializeMergeSettings} accepts {@code .xml} or {@code .zip} on
     * EDT 2026.1 and {@code .zip} ALONE on 2026.2 (this class's javadoc quotes both assertions).
     * What is answered here is the UNION of the versions, so a pre-flight check built on it
     * refuses only what neither version could ever read; a {@code .xml} it accepts still fails
     * inside a 2026.2 launch, loudly, with the platform's own assertion text.
     * <p>
     * <b>It is CASE-SENSITIVE, because the platform's own test is.</b> 2026.2 asserts
     * {@code "zip".equals(FileUtil.getExtension(path))} and 2026.1 branches on the extension the
     * same way - {@code String.equals}, not {@code equalsIgnoreCase} - so {@code rules.ZIP} is a
     * name neither reader accepts. Answering {@code true} for it here let a write produce a
     * perfectly valid archive the platform then refused, which is a file reported as written and
     * usable while being neither. This is deliberately NARROWER than {@link #isZip(Path)}: that
     * one decides how to open a file we already have, this one decides what the PLATFORM will
     * take, and mixing our reading convenience into the platform's contract is what produced the
     * defect.
     * <p>
     * Answered here, next to the two extensions and the reader that honours them, instead of
     * being spelled out again by every tool that hands the platform a path - a second copy of a
     * rule somebody else owns is a copy that goes out of date silently.
     * <p>
     * Says nothing about whether the file exists, is readable, or holds a merge-settings document:
     * those are separate questions with separate answers, and a caller that means "usable" has to
     * ask all of them.
     *
     * @param file the path
     * @return {@code true} when the file name ends with {@code .xml} or {@code .zip}, spelled in
     *         lower case exactly as the platform's reader compares it
     */
    public static boolean hasReadableExtension(Path file)
    {
        String name = rawFileName(file);
        return name != null && (name.endsWith(XML_EXTENSION) || name.endsWith(ZIP_EXTENSION));
    }

    /**
     * @param file the path
     * @return the lower-cased file name, or {@code null} when the path names none
     */
    private static String lowerCaseFileName(Path file)
    {
        String name = rawFileName(file);
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /**
     * @param file the path
     * @return the file name exactly as spelled, or {@code null} when the path names none
     */
    private static String rawFileName(Path file)
    {
        if (file == null || file.getFileName() == null)
        {
            return null;
        }
        return file.getFileName().toString();
    }

    /**
     * Looks in a zipped merge-rules archive for the entry ONE comparison would restore from, and
     * says what the archive holds instead when there is none.
     *
     * <h2>Why the question exists</h2>
     * A zip of merge settings is not one document but a BAG of them, one per comparison, and EDT
     * restores the single entry that belongs to the comparison being launched. Measured from
     * {@code ComparisonManager.deserializeMergeSettingsFromZipFile} - byte for byte the same
     * method in {@code com._1c.g5.v8.dt.compare} 28.0.1 (EDT 2026.1.2) and 29.0.0 (EDT 2026.2.0):
     * the archive is walked, each entry name is put through
     * {@code com._1c.g5.v8.dt.common.StringUtils.removeExtension} and compared with
     * {@code String.equals} against the comparison's own id, and an archive with NO matching entry
     * produces a logged warning and a {@code null}. The launch then proceeds with no decisions at
     * all - nothing is thrown, and the caller is told nothing. That is the case this lookup exists
     * to turn into an answer.
     *
     * <h2>The matching rule is the platform's, spelled out</h2>
     * {@code removeExtension} keeps the part after the last {@code /} or {@code \} and drops
     * everything from the last {@code .} of THAT part, so {@code saved/A_B_C.xml},
     * {@code A_B_C.zip} and a bare {@code A_B_C} all reduce to {@code A_B_C} - while
     * {@code A_B_C.old.xml} reduces to {@code A_B_C.old} and does not match. The comparison is
     * {@code equals}, so it is CASE-SENSITIVE.
     *
     * <h2>The walk is unbounded here, and the accumulation is not</h2>
     * {@link #readZip} bounds its walk because it keeps a name per entry. This answer has to agree
     * with the platform's, and the platform walks every entry, so a bound would turn a large
     * archive into "cannot say" - which is the silent outcome this exists to remove. What costs
     * memory is the names kept for the message, and those stop at {@link #MAX_LISTED_ZIP_ENTRIES}.
     *
     * @param file the archive to look in
     * @param entryId the id this comparison will look for, already without an extension
     * @return what the archive holds
     * @throws IOException when the archive cannot be opened or read
     */
    public static ZipEntryLookup lookUpEntry(Path file, String entryId) throws IOException
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            boolean found = false;
            List<String> kept = new ArrayList<>();
            int total = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // Directory entries are counted and compared like any other, because the
                // platform's own walk does not skip them: a lookup that did could answer "absent"
                // where EDT answers "found", and a false refusal is worse than the silence.
                total++;
                if (kept.size() < MAX_LISTED_ZIP_ENTRIES)
                {
                    kept.add(name);
                }
                if (removeExtension(name).equals(entryId))
                {
                    found = true;
                }
            }
            return new ZipEntryLookup(found, kept, total);
        }
    }

    /**
     * {@code com._1c.g5.v8.dt.common.StringUtils.removeExtension}, reproduced from its bytecode.
     * <p>
     * Reproduced rather than called: that class is EDT's, this codec is the plugin's own reader
     * and is unit-tested without a workbench. The three steps are the platform's exactly - the
     * separator taken as {@code max(lastIndexOf('/'), lastIndexOf('\\'))}, the base as everything
     * after it, and the extension as everything from the base's LAST {@code .}.
     *
     * @param name a zip entry name
     * @return the name a comparison id is matched against
     */
    private static String removeExtension(String name)
    {
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(0, dot);
    }

    /**
     * What {@link MergeRulesCodec#lookUpEntry} saw in one archive.
     * <p>
     * Two facts, kept apart on purpose: whether the entry a comparison will look for is THERE, and
     * what the archive holds if it is not. The second is the only thing that lets a refusal say
     * something a caller can act on - "a zip of somebody else's comparison" and "a zip of the
     * wrong kind entirely" look identical without it.
     */
    public static final class ZipEntryLookup
    {
        private final boolean found;

        private final List<String> kept;

        private final int total;

        private ZipEntryLookup(boolean found, List<String> kept, int total)
        {
            this.found = found;
            this.kept = kept;
            this.total = total;
        }

        /**
         * @return whether an entry the comparison will pick up is in the archive
         */
        public boolean found()
        {
            return found;
        }

        /**
         * Names what the archive holds, for a refusal that has to say what was there instead.
         * <p>
         * Bounded the same way {@link MergeRulesCodec#listEntries} is bounded, and for the same
         * reason: past {@link MergeRulesCodec#MAX_LISTED_ZIP_ENTRIES} names the rest is COUNTED,
         * so a huge archive costs a sentence rather than a page.
         *
         * @return the entry names, or {@code it is empty} when there are none
         */
        public String describeContents()
        {
            if (total == 0)
            {
                return "it is empty"; //$NON-NLS-1$
            }
            if (total <= kept.size())
            {
                return String.join(", ", kept); //$NON-NLS-1$
            }
            return String.join(", ", kept) + " and " + (total - kept.size()) + " more"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static MergeRulesDocument readZip(Path file) throws IOException, MergeRulesFormatException
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            List<ZipEntry> candidates = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int walked = 0;
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                // Counted BEFORE the directory test, and every entry counts. What has to be
                // bounded is the WALK: an archive of nothing but directory entries would skip the
                // accumulation below and still spin this loop once per entry, so a bound that
                // only counted what is kept would not bound the loop it lives in.
                if (++walked > MAX_ZIP_ENTRIES)
                {
                    throw new MergeRulesFormatException(tooManyEntries());
                }
                if (entry.isDirectory())
                {
                    continue;
                }
                names.add(entry.getName());
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(XML_EXTENSION))
                {
                    candidates.add(entry);
                }
            }
            if (candidates.isEmpty() && names.size() == 1)
            {
                // A single entry with no extension is still unambiguous.
                candidates.add(zip.getEntry(names.get(0)));
            }
            if (candidates.size() != 1)
            {
                throw new MergeRulesFormatException("The zip does not hold exactly one merge-settings entry: " //$NON-NLS-1$
                    + (names.isEmpty() ? "it is empty" : listEntries(names)) //$NON-NLS-1$
                    + ". A comparison saves one entry per comparison, named " //$NON-NLS-1$
                    + "'<mainProject>_<otherProject>_<ancestorProject>.xml'; extract the entry you " //$NON-NLS-1$
                    + "mean and read it as .xml."); //$NON-NLS-1$
            }
            ZipEntry entry = candidates.get(0);
            byte[] content;
            try (InputStream in = zip.getInputStream(entry))
            {
                content = readAtMost(in, MAX_DOCUMENT_BYTES);
            }
            if (content == null)
            {
                throw new MergeRulesFormatException(
                    tooLarge("The zip entry '" + entry.getName() + "' expands", //$NON-NLS-1$ //$NON-NLS-2$
                        "Extract the entry, check what it actually holds, and read it as '.xml'.")); //$NON-NLS-1$
            }
            MergeRulesDocument document = parse(new ByteArrayInputStream(content));
            document.setSourceLabel(file + "!" + entry.getName()); //$NON-NLS-1$
            return document;
        }
    }

    /**
     * The refusal for an archive with more entries than {@link #MAX_ZIP_ENTRIES}.
     * <p>
     * It names no entry at all, and that is the point: the names are exactly what was refused to
     * be accumulated, so quoting them here would spend the memory the refusal exists to save.
     *
     * @return the message
     */
    private static String tooManyEntries()
    {
        return "The zip lists more than " + MAX_ZIP_ENTRIES //$NON-NLS-1$
            + " entries and was not read. A merge-settings archive holds ONE entry - the settings " //$NON-NLS-1$
            + "a comparison saved - so an archive this large is not one, and finding an entry in " //$NON-NLS-1$
            + "it means materialising every entry it lists, which would spend the workbench's " //$NON-NLS-1$
            + "heap before anything was even decompressed. Check what the archive actually " //$NON-NLS-1$
            + "holds, extract the entry you mean, and read it as '.xml'."; //$NON-NLS-1$
    }

    /**
     * Lists entry names for the "not exactly one entry" refusal, keeping at most
     * {@link #MAX_LISTED_ZIP_ENTRIES} of them and COUNTING the rest.
     *
     * @param names every non-directory entry name, in the order the archive lists them
     * @return the names to print
     */
    private static String listEntries(List<String> names)
    {
        if (names.size() <= MAX_LISTED_ZIP_ENTRIES)
        {
            return String.join(", ", names); //$NON-NLS-1$
        }
        return String.join(", ", names.subList(0, MAX_LISTED_ZIP_ENTRIES)) //$NON-NLS-1$
            + " and " + (names.size() - MAX_LISTED_ZIP_ENTRIES) + " more"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The one refusal for a source that runs past {@link #MAX_DOCUMENT_BYTES}, whichever source it
     * was.
     * <p>
     * One wording rather than one per container: the bound is the same number for the same reason,
     * and a caller who met it on a zip must not have to learn a second sentence to recognise it on
     * a file. Only the subject and the way out differ, because those really do.
     *
     * @param subject what ran past the bound, as a phrase ending in the verb, e.g.
     *            {@code "The file 'x' runs"}
     * @param advice what the caller can do about it
     * @return the message
     */
    private static String tooLarge(String subject, String advice)
    {
        return subject + " past " + (MAX_DOCUMENT_BYTES / (1024 * 1024)) //$NON-NLS-1$
            + " MB and was not read. A merge-settings file records one line per decision somebody " //$NON-NLS-1$
            + "made, so a real one is orders of magnitude smaller; something this large is not " //$NON-NLS-1$
            + "one, and parsing it would spend the workbench's heap on it. " + advice; //$NON-NLS-1$
    }

    /**
     * The refusal for a document whose OWN serialisation runs past {@link #MAX_DOCUMENT_BYTES}.
     * <p>
     * Kept apart from {@link #tooLarge(String, String)} because the two say different things: that
     * one reports a source that was not read, this one reports a file that was not written - and
     * what the caller most needs to know here is that whatever was already on the path is still
     * there, untouched, which a reading refusal has no occasion to state.
     *
     * @param bytes what the document serialised to
     * @param zipEntryId the zip entry's name, or {@code null} when a bare xml file was being
     *     written - it decides only whether the entry/archive distinction is worth stating
     * @param target what the caller had established about the path, which is what decides whether
     *     there was a file to leave alone
     * @return the message
     */
    private static String tooLargeToWrite(int bytes, String zipEntryId, Target target)
    {
        return "the merge-settings document this call would write runs to " + bytes //$NON-NLS-1$
            + " bytes, past the " + (MAX_DOCUMENT_BYTES / (1024 * 1024)) //$NON-NLS-1$
            + " MB this codec reads back" //$NON-NLS-1$
            + (zipEntryId == null ? "" //$NON-NLS-1$
                : " - which is measured on the entry as it EXPANDS, these same bytes, and not on " //$NON-NLS-1$
                    + "the compressed archive") //$NON-NLS-1$
            + ", so the file would be one this tool could not read again and could not update in " //$NON-NLS-1$
            + "place. Nothing was written, and " //$NON-NLS-1$
            + (target == Target.MAY_BE_REPLACED
                ? "the file already on the path was left exactly as it was." //$NON-NLS-1$
                : "nothing was created on the path.") //$NON-NLS-1$
            + " A real merge-settings file records one line per decision somebody made, so check " //$NON-NLS-1$
            + "what the document this write started from actually holds."; //$NON-NLS-1$
    }

    /**
     * Reads a stream fully, but refuses to grow past a bound.
     * <p>
     * The bound is measured on what is ACTUALLY READ and never on what the container claims: a zip
     * header carries an uncompressed size the archive itself supplies, so trusting it would let a
     * hostile archive declare any size it likes and still inflate without limit. Reading stops one
     * byte past the bound, so an oversized entry costs the bound and not the entry.
     *
     * @param in the stream, closed by the caller
     * @param limit the largest number of bytes that may be returned
     * @return the bytes, or {@code null} when the stream holds more than {@code limit}
     * @throws IOException when the stream cannot be read
     */
    private static byte[] readAtMost(InputStream in, int limit) throws IOException
    {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = in.read(buffer, 0, Math.min(buffer.length, limit + 1 - total))) > 0)
        {
            total += read;
            if (total > limit)
            {
                return null;
            }
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }

    private static XMLInputFactory newSecureFactory()
    {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Harden against XXE / entity-expansion, as JUnitXmlParser does for its DOM parser.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory;
    }

    private static MergeRulesDocument parse(XMLStreamReader reader) throws MergeRulesFormatException
    {
        try
        {
            // FIRST, before a single event is pulled: the declaration is the one property of the
            // document that decides what the REST of it is allowed to contain, and a source this
            // codec cannot re-declare is refused without being read at all.
            rejectUnwritableXmlVersion(reader.getVersion());
            ParsedTree tree = readTree(reader);
            Element rootElement = tree.root;
            if (rootElement == null || !MergeRulesDocument.TAG_SETTINGS.equals(rootElement.tag()))
            {
                throw new MergeRulesFormatException("Not a merge-settings file: the root element is " //$NON-NLS-1$
                    + (rootElement == null ? "missing" : "'" + rootElement.tag() + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ", expected '" + MergeRulesDocument.TAG_SETTINGS //$NON-NLS-1$
                    + "'. A merge-rules file is the one a comparison saves ('Save merge settings')," //$NON-NLS-1$
                    + " not a configuration or a 1C:Enterprise designer settings file."); //$NON-NLS-1$
            }
            String version = rootElement.attribute(MergeRulesDocument.ATTR_FORMAT_VERSION);
            if (!MergeRulesDocument.SUPPORTED_FORMAT_VERSION.equals(version))
            {
                throw new MergeRulesFormatException("Unsupported merge-settings format version: " //$NON-NLS-1$
                    + (version == null ? "the '" + MergeRulesDocument.ATTR_FORMAT_VERSION //$NON-NLS-1$
                        + "' attribute is missing" : "'" + version + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ". This tool reads version '" + MergeRulesDocument.SUPPORTED_FORMAT_VERSION //$NON-NLS-1$
                    + "', which is also the only version EDT's own reader accepts."); //$NON-NLS-1$
            }
            rejectDuplicateNodeKeys(rootElement);
            return MergeRulesDocument.of(rootElement, tree.prolog, tree.epilog);
        }
        catch (XMLStreamException e)
        {
            throw new MergeRulesFormatException(notXml(e), e);
        }
        finally
        {
            closeQuietly(reader);
        }
    }

    /**
     * Reads the whole document into an element tree, keeping character data, comments and
     * processing instructions IN DOCUMENT ORDER among the child elements and BYTE FOR BYTE.
     * <p>
     * The runs are accumulated in one buffer, but that buffer is FLUSHED INTO THE ELEMENT THAT
     * OWNS IT at every element boundary, which is the difference that matters: a single buffer
     * merely cleared at each boundary loses the run that precedes a child element and re-attaches
     * the run that follows one to the parent as a whole - so a section with mixed content came
     * back with its leading text deleted and its trailing text moved in front of every child. The
     * codec's promise is that a payload block it does not interpret survives a rewrite verbatim,
     * and mixed content is precisely where a payload block puts its text.
     * <p>
     * <b>A comment and a processing instruction are flushed the same way, and BEFORE the node
     * itself is appended</b>: the run that precedes a comment belongs in front of it, so a buffer
     * emptied only at an element boundary would move that text past the comment and reorder the
     * very payload this codec promises to carry through.
     * <p>
     * Every run is kept exactly as read; which of them are LAYOUT is decided per element, once
     * that element is complete, by {@code separateLayoutFromContent}.
     *
     * @param reader the stream reader positioned before the document
     * @return the root element together with whatever stood beside it; the root is {@code null}
     *             for an empty document
     * @throws XMLStreamException when the stream is not well-formed XML
     * @throws MergeRulesFormatException when the document nests deeper than
     *             {@link #MAX_ELEMENT_DEPTH}
     */
    private static ParsedTree readTree(XMLStreamReader reader)
        throws XMLStreamException, MergeRulesFormatException
    {
        ParsedTree tree = new ParsedTree();
        Deque<Element> stack = new ArrayDeque<>();
        StringBuilder pending = new StringBuilder();
        while (reader.hasNext())
        {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT)
            {
                // Whatever has been read so far belongs to the element still open above.
                flushText(stack.peek(), pending);
                rejectNamespaceUse(reader);
                if (stack.size() >= MAX_ELEMENT_DEPTH)
                {
                    throw new MergeRulesFormatException(tooDeep(reader.getLocalName()));
                }
                Element element = new Element(reader.getLocalName());
                for (int i = 0; i < reader.getAttributeCount(); i++)
                {
                    element.attribute(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                }
                if (stack.isEmpty())
                {
                    tree.root = element;
                }
                else
                {
                    stack.peek().children().add(element);
                }
                stack.push(element);
            }
            else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
            {
                pending.append(reader.getText());
            }
            else if (event == XMLStreamConstants.COMMENT)
            {
                flushText(stack.peek(), pending);
                tree.add(stack.peek(), Element.comment(reader.getText()));
            }
            else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION)
            {
                flushText(stack.peek(), pending);
                tree.add(stack.peek(),
                    Element.processingInstruction(reader.getPITarget(), reader.getPIData()));
            }
            else if (event == XMLStreamConstants.END_ELEMENT)
            {
                flushText(stack.peek(), pending);
                separateLayoutFromContent(stack.pop());
            }
        }
        return tree;
    }

    /**
     * Refuses a document in which two SIBLING {@code <Node>} elements carry the same {@code Key}.
     *
     * <h2>Why it is a refusal and not a merge</h2>
     * The node tree is a map addressed by key, and such a document says two things about one
     * address. Reading it means picking one, and the pick is invisible: EDT resolves a node by
     * string equality and stops at the first match, so the first sibling is the one every reader
     * sees, while the second sits in the file with a rule of its own that nothing will ever apply
     * and nothing will ever report. A rewrite would carry both forward, which is the one thing
     * this codec promises NOT to do quietly - and dropping one instead would delete a decision
     * somebody wrote. Neither is ours to choose, so the file goes back to the person who edited
     * it, in the same shape as every other grammar refusal here.
     *
     * <h2>The scan does not follow the addressing, it IS the addressing</h2>
     * Twice already this scan judged a shape no request can reach, and both times because it
     * answered a question {@link MergeRulesDocument} answers too - and answered it differently.
     * It judged every {@code MergeSettings} element while the document reads the first one; then
     * it treated every keyed {@code Node} in the container as a way in, while the document enters
     * at {@link MergeRulesDocument#ROOT_KEY} and nowhere else. A third wording would have been a
     * third instance, so the wording is gone: the container, the entry and the pick are asked of
     * {@code MergeRulesDocument} itself - {@code findContainer}, {@code findRoot},
     * {@code nodeChildren} and {@code findNode} - and this scan cannot address anything the
     * document cannot, because it does not know how to.
     * <p>
     * What it adds is the ONE question addressing cannot ask: a lookup picks the FIRST candidate
     * and can never see that there was a second, so the scan looks for that second candidate at
     * every element a lookup can stand on. Two consequences follow from the entry rule and are
     * worth naming, because each of them used to go the other way:
     * <ul>
     *   <li>a second {@code <Node Key="$$Root$$">} in the container IS refused - a rule written to
     *       the root updates the first one and everything under the second is addressed by
     *       nothing;</li>
     *   <li>a {@code <Node>} that sits BESIDE the root under any other key is not an entry at all,
     *       so neither it nor anything below it is judged. Refusing there named a pair no request
     *       can reach, at a level and under a path that do not exist.</li>
     * </ul>
     * A payload section this plugin does not interpret is likewise not walked: {@code nodeChildren}
     * answers the children of ONE element, so an element named {@code Node} inside a
     * {@code Properties} block is somebody else's content and is never reached.
     *
     * <h2>What it deliberately does NOT refuse</h2>
     * Two sibling {@code <Node>} elements that BOTH lack a {@code Key}. They are not two spellings
     * of one address - they have no address, so no rule can ever be written to them and no lookup
     * can confuse one for the other. That is a different question, it is pre-existing, and
     * answering it here would refuse files over a shape this refusal is not about.
     *
     * @param settings the parsed {@code Settings} root
     * @throws MergeRulesFormatException when two sibling nodes share a key
     */
    private static void rejectDuplicateNodeKeys(Element settings) throws MergeRulesFormatException
    {
        Element container = MergeRulesDocument.findContainer(settings);
        if (container == null)
        {
            // No container is no node tree, so there is no address to be ambiguous about.
            return;
        }
        rejectDuplicateRoots(container);
        Element root = MergeRulesDocument.findRoot(container);
        if (root == null)
        {
            // The container exposes exactly one address and the file does not carry it: nothing
            // below is reachable, so nothing below is judged.
            return;
        }
        rejectDuplicateSiblingKeys(root, List.of(MergeRulesDocument.ROOT_KEY));
    }

    /**
     * Level 0: the container, where the only address is the root.
     * <p>
     * {@code findRoot} picks the FIRST node carrying {@link MergeRulesDocument#ROOT_KEY}, so a
     * second one holds a subtree that every lookup, decision and write walks straight past. Every
     * other node here is not an address at all and is not counted - two of them sharing a key is
     * two unreachable nodes, not two spellings of one reachable address.
     *
     * @param container the {@code MergeSettings} element the document reads
     * @throws MergeRulesFormatException when the container carries the root key twice
     */
    private static void rejectDuplicateRoots(Element container) throws MergeRulesFormatException
    {
        boolean seen = false;
        for (Element node : MergeRulesDocument.nodeChildren(container))
        {
            if (!MergeRulesDocument.ROOT_KEY.equals(node.attribute(MergeRulesDocument.ATTR_KEY)))
            {
                continue;
            }
            if (seen)
            {
                throw new MergeRulesFormatException(
                    duplicateNodeKey(MergeRulesDocument.ROOT_KEY, List.of()));
            }
            seen = true;
        }
    }

    /**
     * One level of the scan, below the root: the siblings here first, then each of them in turn.
     * <p>
     * Breadth before depth on purpose - the shallowest collision is the one a reader can act on,
     * and reporting a deep one first would name a node whose own ancestor is already ambiguous.
     * <p>
     * The descent goes through {@code MergeRulesDocument.findNode} rather than through the child
     * the loop above happens to hold: what is walked is then, by construction, the element a
     * lookup for that key would land on.
     *
     * @param parent the element whose {@code Node} children are this level
     * @param path the keys of the ancestors, starting at {@link MergeRulesDocument#ROOT_KEY}
     * @throws MergeRulesFormatException when two sibling nodes share a key
     */
    private static void rejectDuplicateSiblingKeys(Element parent, List<String> path)
        throws MergeRulesFormatException
    {
        Set<String> keys = new LinkedHashSet<>();
        for (Element child : MergeRulesDocument.nodeChildren(parent))
        {
            String key = child.attribute(MergeRulesDocument.ATTR_KEY);
            if (key == null)
            {
                // Unaddressable, so it is not a PARENT either: findNode matches a child on tag AND
                // key, so no lookup can stand on this node and nothing below it is reachable by
                // any request. Whatever it holds is already outside the addressing this refusal is
                // about.
                continue;
            }
            if (!keys.add(key))
            {
                throw new MergeRulesFormatException(duplicateNodeKey(key, path));
            }
        }
        for (String key : keys)
        {
            List<String> here = new ArrayList<>(path);
            here.add(key);
            rejectDuplicateSiblingKeys(MergeRulesDocument.findNode(parent, key), here);
        }
    }

    /**
     * @param key the key two siblings share
     * @param path the keys of their ancestors, starting at {@link MergeRulesDocument#ROOT_KEY};
     *            empty when the pair IS the root
     * @return the refusal, naming the key AND the level it sits at
     */
    private static String duplicateNodeKey(String key, List<String> path)
    {
        String where = path.isEmpty()
            ? "directly under '<" + MergeRulesDocument.TAG_MERGE_SETTINGS //$NON-NLS-1$ //$NON-NLS-2$
                + ">', where the only address is the '" + MergeRulesDocument.ROOT_KEY //$NON-NLS-1$
                + "' node itself" //$NON-NLS-1$
            : "under '" + String.join(" / ", path) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return "Malformed merge-settings file: two '<" + MergeRulesDocument.TAG_NODE //$NON-NLS-1$
            + ">' elements carry the same " + MergeRulesDocument.ATTR_KEY + " '" + key //$NON-NLS-1$ //$NON-NLS-2$
            + "' as siblings at level " + path.size() + " (" + where + "). Level 0 is the '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + MergeRulesDocument.ROOT_KEY + "' node, 1 a feature collection, 2 a top object. EDT " //$NON-NLS-1$
            + "matches nodes by string equality and stops at the first one, so only that one is " //$NON-NLS-1$
            + "ever found and the other holds a rule nothing will apply and nothing will report. " //$NON-NLS-1$
            + "Open the file, merge the two elements into one keeping the rule you want, and run " //$NON-NLS-1$
            + "this again."; //$NON-NLS-1$
    }

    /**
     * Refuses a source whose XML declaration names a version this codec cannot hand back.
     *
     * <h2>Why the DECLARATION is what is judged, and not the characters</h2>
     * The serializer prints one fixed declaration, {@link #XML_DECLARATION}, so every rewrite comes
     * back as XML 1.0 whatever the source said. That is not a layout difference of the kind this
     * class's javadoc allows: the version decides which characters the document may hold at all,
     * so re-declaring it hands the next reader a grammar the file was never checked against.
     * <p>
     * <b>The route by which that goes wrong has exactly one entrance, and this is it.</b> Measured
     * on this JDK's StAX reader, with the same factory settings {@link #newSecureFactory()} uses:
     * <ul>
     * <li>{@code &#x1;} in character data or in an attribute value parses under a {@code 1.1}
     * declaration and arrives in the model as U+0001, and the same reference under a {@code 1.0}
     * declaration is refused - {@code Character reference "&#x1" is an invalid XML character};</li>
     * <li>a LITERAL U+0001 is refused under BOTH versions, in content and in a comment alike, so a
     * restricted character can only ever enter through a character reference;</li>
     * <li>a declaration of {@code 1.2} never reaches this method - the reader refuses it itself
     * with {@code XML version "1.2" is not supported}.</li>
     * </ul>
     * So refusing the declaration closes the whole family at its single entrance, and no scan of
     * the parsed characters is needed to do it: nothing 1.0 cannot spell can be in a document that
     * declared 1.0, and nothing else is read.
     * <p>
     * <b>A 1.1 source with only ordinary characters is refused too</b>, and that is deliberate
     * rather than collateral. Two reasons, either of which is enough. The rewrite would silently
     * REPLACE the declaration, which is content of the file this codec promises to give back
     * unchanged. And XML 1.1 normalises U+0085 and U+2028 to a line feed where 1.0 does not, so a
     * 1.1 source's line ends come back differently and a rewrite would bake that in under a
     * declaration that never asked for it. Judging the repertoire instead would wave both through
     * while looking stricter.
     * <p>
     * <b>What is NOT refused:</b> a document with no declaration at all. The reader then applies
     * 1.0's rules - {@code getVersion()} answers {@code null} - so the parsed document is one this
     * codec can write, and adding the declaration on the way out is the canonical layout this class
     * has always produced.
     *
     * @param version the version the declaration named, or {@code null} when there was none
     * @throws MergeRulesFormatException when the version is neither absent nor
     *             {@link #WRITABLE_XML_VERSION}
     */
    private static void rejectUnwritableXmlVersion(String version) throws MergeRulesFormatException
    {
        if (version == null || WRITABLE_XML_VERSION.equals(version))
        {
            return;
        }
        throw new MergeRulesFormatException(unwritableXmlVersion(version));
    }

    /**
     * Refuses one element that uses an XML namespace, in any of the three shapes it can take.
     * <p>
     * <b>The three shapes are checked separately because a reader reports them separately, and
     * each of them breaks the round trip on its own.</b> A namespace DECLARATION is not an
     * attribute - {@code getAttributeCount()} does not count it - so it is never read and can
     * never be written back; a PREFIXED ELEMENT is reported under its local name, so it comes back
     * with the prefix gone; and a PREFIXED ATTRIBUTE shares its local name with an unprefixed
     * sibling, so the two land on ONE key in the attribute map and the second silently DESTROYS
     * the first. The third is the reason this is a refusal rather than a stated difference: the
     * other two rewrite the file, that one deletes a value out of it.
     * <p>
     * <b>The order is prefix first, declaration last</b>, so the refusal names the thing that is
     * actually in the way rather than the declaration that merely enabled it - and so each of the
     * three checks is reachable on its own, since a prefixed name normally carries its declaration
     * on the very same element. The implicit {@code xml} prefix needs no declaration at all
     * ({@code xml:space="preserve"} beside a plain {@code space} attribute is the collision above
     * with nothing declared anywhere), which is the case only the attribute check can catch.
     *
     * @param reader the stream reader positioned on a START_ELEMENT
     * @throws MergeRulesFormatException when the element carries a prefix, a prefixed attribute or
     *             a namespace declaration
     */
    private static void rejectNamespaceUse(XMLStreamReader reader) throws MergeRulesFormatException
    {
        String elementPrefix = reader.getPrefix();
        if (elementPrefix != null && !elementPrefix.isEmpty())
        {
            throw new MergeRulesFormatException(usesNamespace("the element '<" + elementPrefix //$NON-NLS-1$
                + ':' + reader.getLocalName() + ">' is prefixed")); //$NON-NLS-1$
        }
        for (int i = 0; i < reader.getAttributeCount(); i++)
        {
            String attributePrefix = reader.getAttributePrefix(i);
            if (attributePrefix != null && !attributePrefix.isEmpty())
            {
                throw new MergeRulesFormatException(usesNamespace("the attribute '" //$NON-NLS-1$
                    + attributePrefix + ':' + reader.getAttributeLocalName(i) + "' on '<" //$NON-NLS-1$
                    + reader.getLocalName() + ">' is prefixed")); //$NON-NLS-1$
            }
        }
        if (reader.getNamespaceCount() > 0)
        {
            String declaredPrefix = reader.getNamespacePrefix(0);
            String declaration = declaredPrefix == null || declaredPrefix.isEmpty()
                ? "xmlns" : "xmlns:" + declaredPrefix; //$NON-NLS-1$ //$NON-NLS-2$
            throw new MergeRulesFormatException(usesNamespace("'<" + reader.getLocalName() //$NON-NLS-1$
                + ">' declares " + declaration + "=\"" + reader.getNamespaceURI(0) + '"')); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * What one parse produced: the root element and the nodes that stood BESIDE it.
     * <p>
     * The two lists exist because XML puts a comment or a processing instruction outside the root
     * when it stands before or after it, and an element cannot hold a sibling. Which list a node
     * belongs to is decided by whether the root has been seen yet, so the order is the document's
     * own and needs no second pass.
     */
    private static final class ParsedTree
    {
        private Element root;

        private final List<Element> prolog = new ArrayList<>();

        private final List<Element> epilog = new ArrayList<>();

        /**
         * Files one node where the document put it.
         *
         * @param owner the element still open, or {@code null} when the node stands outside the
         *            root
         * @param node the comment or processing instruction
         */
        void add(Element owner, Element node)
        {
            if (owner != null)
            {
                owner.children().add(node);
            }
            else if (root == null)
            {
                prolog.add(node);
            }
            else
            {
                epilog.add(node);
            }
        }
    }

    /**
     * Attaches the character data read so far to the element that owns it, exactly as read.
     *
     * @param owner the element the run belongs to, or {@code null} for text outside the root
     * @param pending the accumulated run, cleared by this call
     */
    private static void flushText(Element owner, StringBuilder pending)
    {
        if (pending.length() == 0)
        {
            return;
        }
        String content = pending.toString();
        pending.setLength(0);
        if (owner != null)
        {
            owner.children().add(Element.text(content));
        }
    }

    /**
     * Decides, for one finished element, whether the character data it holds is LAYOUT or
     * CONTENT - and drops it only in the first case.
     * <p>
     * <b>The rule is asked of the ELEMENT, not of the run: an element whose character data is
     * entirely whitespace is laid out, and an element with any non-whitespace character data is
     * mixed content - in which case ALL of its character data is content and is kept byte for
     * byte.</b> Indentation between two child elements is whitespace and nothing else, so a file
     * in the canonical layout loses exactly its layout and gets an identical one back, which is
     * what keeps the round trip byte-identical. As soon as a run says something, the whitespace
     * beside it stops being decoration: in {@code <Payload>Hello <Child/> world</Payload>} the
     * spaces around the child are as much a part of the text as the letters, and trimming them
     * rewrites a payload block this codec promises to carry through verbatim. Deciding run by run
     * cannot express that - the space between two children of a mixed element is whitespace-only
     * and still content - which is why the question is asked once per element.
     * <p>
     * The two cases that keep the behaviour they always had: an element whose whole content is
     * text keeps that text byte for byte even when it is blank (a {@code Properties} entry's value
     * is data), unless the element is STRUCTURAL, where a blank body is only how somebody laid the
     * file out.
     * <p>
     * <b>What the rule cannot tell apart</b> is significant whitespace that is ENTIRELY
     * whitespace - {@code <A> <B/> </A>} where the spaces are meant. XML carries no in-band signal
     * for it other than {@code xml:space="preserve"}, which never reaches this rule: an attribute
     * with a prefix means the document uses a namespace, and such a document is REFUSED at the
     * parse (stated on the class). The merge-settings format has no such element; one that had
     * would need the prefix modelled first, which is what that refusal declines to fake.
     *
     * @param element the element whose children are complete
     */
    private static void separateLayoutFromContent(Element element)
    {
        boolean hasLaidOutChild = false;
        boolean hasContent = false;
        for (Element child : element.children())
        {
            if (child.isText())
            {
                hasContent |= !child.textValue().isBlank();
            }
            else
            {
                // An element, a comment and a processing instruction alike: each of them is a node
                // the canonical layout puts on a line of its own, so the whitespace beside it is
                // the indentation that put it there.
                hasLaidOutChild = true;
            }
        }
        if (!hasLaidOutChild)
        {
            if (!hasContent && isStructural(element))
            {
                element.children().clear();
            }
            return;
        }
        if (!hasContent)
        {
            element.children().removeIf(Element::isText);
        }
    }

    /**
     * The one refusal every namespace shape ends in, naming what was found and what to do.
     *
     * @param found what the reader saw, phrased as a clause
     * @return the message
     */
    private static String usesNamespace(String found)
    {
        return "The merge-settings document uses an XML namespace (" + found //$NON-NLS-1$
            + ") and was not read. The merge-settings format declares none: EDT's own serializer " //$NON-NLS-1$
            + "never writes a namespace and its reader keys on local names, so a prefix here comes " //$NON-NLS-1$
            + "from a hand edit or a block pasted in from somewhere else. Reading it would not " //$NON-NLS-1$
            + "carry it through: a declaration is not an attribute and cannot be written back, a " //$NON-NLS-1$
            + "prefixed element comes back without its prefix, and two attributes that differ only " //$NON-NLS-1$
            + "by their prefix share one name - the second DESTROYS the first and its value is " //$NON-NLS-1$
            + "gone. Refusing the file is what keeps that payload intact, because a rewrite of it " //$NON-NLS-1$
            + "would not. Save the settings again from EDT's comparison window ('Save merge " //$NON-NLS-1$
            + "settings'), or remove the foreign block from the file, and read it again."; //$NON-NLS-1$
    }

    /**
     * The refusal for a source declaring an XML version this codec cannot write back.
     *
     * @param version the version the declaration named
     * @return the message
     */
    private static String unwritableXmlVersion(String version)
    {
        return "The merge-settings document declares XML version '" + version //$NON-NLS-1$
            + "' and was not read. This tool writes one declaration - '" + WRITABLE_XML_VERSION //$NON-NLS-1$
            + "' - so rewriting the file would hand it back declaring a version it was never " //$NON-NLS-1$
            + "checked against, and that is a different grammar rather than a difference in " //$NON-NLS-1$
            + "layout. XML 1.1 admits characters 1.0 has no spelling for: a '&#x1;' in a value " //$NON-NLS-1$
            + "reads perfectly well under a '1.1' declaration and is an invalid character under a " //$NON-NLS-1$
            + "'1.0' one, so the rewritten file would be one this tool - and EDT - could no " //$NON-NLS-1$
            + "longer read, after reporting the rules as written. 1.1 also folds U+0085 and " //$NON-NLS-1$
            + "U+2028 into line feeds, which 1.0 keeps as themselves. Save the settings again " //$NON-NLS-1$
            + "from EDT's comparison window ('Save merge settings'), which writes '" //$NON-NLS-1$
            + WRITABLE_XML_VERSION + "'; or, if the file really holds nothing but ordinary " //$NON-NLS-1$
            + "characters, change its declaration to '" + WRITABLE_XML_VERSION //$NON-NLS-1$
            + "' yourself and read it again."; //$NON-NLS-1$
    }

    private static String tooDeep(String tag)
    {
        return "The merge-settings document nests elements more than " + MAX_ELEMENT_DEPTH //$NON-NLS-1$
            + " levels deep (at '<" + tag + ">') and was not read. A merge-settings file is FLAT: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "the deepest tree the platform writes is a root, a feature collection, an object and " //$NON-NLS-1$
            + "its positional children, so a document this deep is not one - and every walk over " //$NON-NLS-1$
            + "it re-enters once per level, which would spend the workbench's stack instead of " //$NON-NLS-1$
            + "reporting a problem. Check what the file actually holds and read the section you " //$NON-NLS-1$
            + "mean."; //$NON-NLS-1$
    }

    private static boolean isStructural(Element element)
    {
        return MergeRulesDocument.TAG_NODE.equals(element.tag())
            || MergeRulesDocument.TAG_MERGE_SETTINGS.equals(element.tag())
            || MergeRulesDocument.TAG_SETTINGS.equals(element.tag());
    }

    /**
     * Writes one element.
     * <p>
     * An element that holds CONTENT character data (see
     * {@code separateLayoutFromContent}) is written INLINE: its runs go out exactly as
     * they are held and its child elements get no line of their own, because any newline or
     * indentation inserted between a run and the child beside it would land INSIDE the element's
     * character data and change the value the next reader parses. Everything else gets the
     * canonical layout, generated from the depth.
     *
     * @param out the buffer
     * @param element the element to write
     * @param depth nesting depth, used for the canonical indentation
     * @param inline whether this element sits inside another element's character data, and so may
     *            neither indent itself nor end its own line
     */
    private static void writeElement(StringBuilder out, Element element, int depth, boolean inline)
    {
        if (!inline)
        {
            indent(out, depth);
        }
        out.append('<').append(element.tag());
        for (Map.Entry<String, String> attribute : element.attributes().entrySet())
        {
            out.append(' ').append(attribute.getKey()).append("=\"") //$NON-NLS-1$
                .append(escapeAttribute(attribute.getValue())).append('"');
        }
        List<Element> content = element.children();
        if (content.isEmpty())
        {
            out.append("/>"); //$NON-NLS-1$
            endLine(out, inline);
            return;
        }
        out.append('>');
        if (isMixed(content))
        {
            for (Element child : content)
            {
                writeNode(out, child, depth + 1, true);
            }
            closeTag(out, element, inline);
            return;
        }
        if (content.get(0).isText())
        {
            // The element's whole content is its value: it goes back on the one line it came from.
            for (Element child : content)
            {
                out.append(escapeCharacterData(child.textValue()));
            }
            closeTag(out, element, inline);
            return;
        }
        out.append(NEW_LINE);
        for (Element child : content)
        {
            writeNode(out, child, depth + 1, false);
        }
        indent(out, depth);
        closeTag(out, element, inline);
    }

    /**
     * Writes one node of any kind.
     * <p>
     * <b>A comment and a processing instruction go back VERBATIM, unescaped.</b> Nothing else
     * would be correct: XML forbids {@code --} inside a comment and {@code ?>} inside a
     * processing instruction, so a parser cannot report a body this method would have to escape,
     * and escaping one anyway would rewrite payload the codec promises to carry through
     * unchanged. The only nodes this codec ever creates of these two kinds are the ones its own
     * reader produced, which is where that guarantee comes from - a node built by hand out of
     * text that spells a delimiter would produce a document no reader accepts, and is not a shape
     * this codec offers a way to reach.
     *
     * @param out the buffer
     * @param node the node to write
     * @param depth nesting depth, used for the canonical indentation
     * @param inline whether the node sits inside another element's character data, and so may
     *            neither indent itself nor end its own line
     */
    private static void writeNode(StringBuilder out, Element node, int depth, boolean inline)
    {
        if (node.isText())
        {
            out.append(escapeCharacterData(node.textValue()));
            return;
        }
        if (node.isComment())
        {
            if (!inline)
            {
                indent(out, depth);
            }
            out.append("<!--").append(node.textValue()).append("-->"); //$NON-NLS-1$ //$NON-NLS-2$
            endLine(out, inline);
            return;
        }
        if (node.isProcessingInstruction())
        {
            if (!inline)
            {
                indent(out, depth);
            }
            out.append("<?").append(node.target()); //$NON-NLS-1$
            String data = node.textValue();
            if (data != null && !data.isEmpty())
            {
                // The separator is a single space, and it is REGENERATED rather than preserved:
                // the parser reports the data with the separator already consumed, so how many
                // spaces the file spelled is not knowable from what was read.
                out.append(' ').append(data);
            }
            out.append("?>"); //$NON-NLS-1$
            endLine(out, inline);
            return;
        }
        writeElement(out, node, depth, inline);
    }

    /**
     * Whether these children are mixed content - character data AND a node beside it.
     * <p>
     * A comment and a processing instruction count on the same side as a child element: they too
     * sit INSIDE the character data, so laying them out on lines of their own would insert
     * newlines and indentation into a value the next reader parses.
     *
     * @param content one element's children
     * @return {@code true} when both kinds are present
     */
    private static boolean isMixed(List<Element> content)
    {
        boolean text = false;
        boolean laidOut = false;
        for (Element child : content)
        {
            text |= child.isText();
            laidOut |= !child.isText();
        }
        return text && laidOut;
    }

    private static void closeTag(StringBuilder out, Element element, boolean inline)
    {
        out.append("</").append(element.tag()).append('>'); //$NON-NLS-1$
        endLine(out, inline);
    }

    private static void endLine(StringBuilder out, boolean inline)
    {
        if (!inline)
        {
            out.append(NEW_LINE);
        }
    }

    private static void indent(StringBuilder out, int depth)
    {
        for (int i = 0; i < depth; i++)
        {
            out.append(INDENT);
        }
    }

    /**
     * Escapes a run of character data.
     * <p>
     * A carriage return is escaped instead of being written as itself. XML normalises line ends
     * (spec 2.11) BEFORE a parser reports any character data, so a literal CR in the file comes
     * back as LF - a value this codec promises to keep verbatim would silently change on the next
     * read, and a second rewrite would then differ from the first. A CR is in the model only
     * because the file spelled it {@code &#13;}, and that is how it goes back.
     *
     * @param value the character data
     * @return the escaped text
     */
    private static String escapeCharacterData(String value)
    {
        return escapeText(value).replace("\r", "&#13;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String escapeText(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    private static String escapeAttribute(String value)
    {
        return escapeText(value == null ? "" : value).replace("\"", "&quot;") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    private static String notXml(XMLStreamException e)
    {
        return "The merge-settings file could not be parsed as XML: " //$NON-NLS-1$
            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static void closeQuietly(XMLStreamReader reader)
    {
        try
        {
            reader.close();
        }
        catch (XMLStreamException e)
        {
            // Nothing actionable: the content has already been read (or failed to parse).
        }
    }

    /**
     * Answers what a path's symbolic link points at.
     */
    @FunctionalInterface
    interface LinkReader
    {
        /**
         * @param path the path to look at
         * @return the destination recorded in the link, exactly as recorded (so possibly
         *         relative), or {@code null} when the path is not a symbolic link
         * @throws IOException when the link cannot be read
         */
        Path destinationOf(Path path) throws IOException;
    }

    /**
     * Raised when a file is not a merge-settings document this codec can read. Carries a message
     * naming the value that was found and what to do about it, so a tool can surface it as-is.
     */
    public static final class MergeRulesFormatException extends Exception
    {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the actionable message
         */
        public MergeRulesFormatException(String message)
        {
            super(message);
        }

        /**
         * Creates the exception.
         *
         * @param message the actionable message
         * @param cause the underlying failure
         */
        public MergeRulesFormatException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
