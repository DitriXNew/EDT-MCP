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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Element;

/**
 * Reads and writes EDT's merge-settings file into / out of {@link MergeRulesDocument}.
 * <p>
 * <b>Reading accepts both containers the platform accepts.</b>
 * {@code IComparisonManager.deserializeMergeSettings} asserts the name ends in {@code .xml} or
 * {@code .zip}; the xml form is parsed directly, and the zip form is a container the comparison
 * editor saves, one entry per comparison. NOTE the trap measured in that same method: the
 * platform picks the zip entry whose name (minus extension) equals
 * {@code <mainProject>_<otherProject>_<ancestorProject>} and merely LOGS A WARNING when no entry
 * matches - i.e. a zip whose entry is named anything else is silently ignored by EDT. That is
 * why this codec reads a zip but writes only xml: an entry name that would actually be picked up
 * is knowable only from a live comparison.
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
 * Two differences are known and stated rather than implied, because both are invisible to any
 * XML reader: an element with empty content is re-emitted self-closing ({@code <A></A>} becomes
 * {@code <A/>} - the same empty content), and a namespace PREFIX is not modelled (this format
 * declares none; local names are what both readers key on).
 */
public final class MergeRulesCodec
{
    /** Extension of the plain-xml form. */
    public static final String XML_EXTENSION = ".xml"; //$NON-NLS-1$

    /** Extension of the zipped form the comparison editor saves. */
    public static final String ZIP_EXTENSION = ".zip"; //$NON-NLS-1$

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"; //$NON-NLS-1$

    private static final String INDENT = "  "; //$NON-NLS-1$

    private static final String NEW_LINE = "\n"; //$NON-NLS-1$

    /**
     * Largest number of bytes one zip entry may expand to before this codec stops reading it.
     * <p>
     * The number is a ceiling on damage, not a guess at a real file. A merge-settings file is
     * SPARSE - one {@code Node} line per decision somebody actually made, around a hundred bytes -
     * so even a configuration-wide set of tens of thousands of decisions stays in the low
     * megabytes; the files saved off real comparisons are orders of magnitude under this. What the
     * bound is for is the other direction: a zip entry is decompressed by the JVM the workbench
     * itself runs in, so a small archive of highly compressible bytes would otherwise exhaust
     * EDT's heap on the way to being parsed, and the IDE - not just this call - would go down.
     */
    private static final int MAX_ZIP_ENTRY_BYTES = 16 * 1024 * 1024;

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
        try (InputStream in = Files.newInputStream(file))
        {
            MergeRulesDocument document = parse(in);
            document.setSourceLabel(file.toString());
            return document;
        }
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
        writeElement(out, document.settings(), 0, false);
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
     * Writes a document as UTF-8 xml, creating the parent directories when needed.
     * <p>
     * The bytes land in a sibling temporary file that is then moved over the target, so an
     * update-in-place (reading a file and writing it back) cannot leave a half-written file
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
     * <b>The move itself always replaces</b>, and it has to: it moves the temporary onto the
     * target. What {@link Target#MUST_NOT_EXIST} adds is a RESERVATION taken before a single byte
     * is written - {@code Files.createFile}, the create-if-absent the filesystem performs as one
     * indivisible operation - so the file the move then replaces is this call's own reservation
     * and never somebody else's rules. A reservation that is not consumed is removed again, so a
     * failed write leaves the path as free as it found it.
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
        // Created in the TARGET's directory, never in the system temp area: the move over the
        // target has to stay within one filesystem to be atomic. The name carries the target's
        // own name so a leftover is traceable to the write that left it.
        Path temporary = Files.createTempFile(parent, resolved.getFileName().toString() + '.',
            ".tmp"); //$NON-NLS-1$
        // The temporary lives in the CALLER's directory, so a failure that leaves it behind leaves
        // litter in a place the caller owns. Every failing exit removes it; the successful one does
        // not need to, because the move consumed it.
        try
        {
            Files.write(temporary, serialize(document).getBytes(StandardCharsets.UTF_8));
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
            cleanUp(temporary, e);
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
     * Whether a path names the zipped form.
     *
     * @param file the path
     * @return {@code true} when the file name ends with {@code .zip}, case-insensitively
     */
    public static boolean isZip(Path file)
    {
        return file != null && file.getFileName() != null
            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ZIP_EXTENSION);
    }

    private static MergeRulesDocument readZip(Path file) throws IOException, MergeRulesFormatException
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            List<ZipEntry> candidates = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
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
                    + (names.isEmpty() ? "it is empty" : String.join(", ", names)) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". A comparison saves one entry per comparison, named " //$NON-NLS-1$
                    + "'<mainProject>_<otherProject>_<ancestorProject>.xml'; extract the entry you " //$NON-NLS-1$
                    + "mean and read it as .xml."); //$NON-NLS-1$
            }
            ZipEntry entry = candidates.get(0);
            byte[] content;
            try (InputStream in = zip.getInputStream(entry))
            {
                content = readAtMost(in, MAX_ZIP_ENTRY_BYTES);
            }
            if (content == null)
            {
                throw new MergeRulesFormatException("The zip entry '" + entry.getName() //$NON-NLS-1$
                    + "' expands past " + (MAX_ZIP_ENTRY_BYTES / (1024 * 1024)) //$NON-NLS-1$
                    + " MB and was not read. A merge-settings file records one line per decision " //$NON-NLS-1$
                    + "somebody made, so a real one is orders of magnitude smaller; an archive that " //$NON-NLS-1$
                    + "unpacks to more than this is not one, and unpacking it would spend the " //$NON-NLS-1$
                    + "workbench's heap on it. Extract the entry, check what it actually holds, and " //$NON-NLS-1$
                    + "read it as '.xml'."); //$NON-NLS-1$
            }
            MergeRulesDocument document = parse(new ByteArrayInputStream(content));
            document.setSourceLabel(file + "!" + entry.getName()); //$NON-NLS-1$
            return document;
        }
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
            Element rootElement = readTree(reader);
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
            return MergeRulesDocument.of(rootElement);
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
     * Reads the whole document into an element tree, keeping character data IN DOCUMENT ORDER
     * among the child elements and BYTE FOR BYTE.
     * <p>
     * The runs are accumulated in one buffer, but that buffer is FLUSHED INTO THE ELEMENT THAT
     * OWNS IT at every element boundary, which is the difference that matters: a single buffer
     * merely cleared at each boundary loses the run that precedes a child element and re-attaches
     * the run that follows one to the parent as a whole - so a section with mixed content came
     * back with its leading text deleted and its trailing text moved in front of every child. The
     * codec's promise is that a payload block it does not interpret survives a rewrite verbatim,
     * and mixed content is precisely where a payload block puts its text.
     * <p>
     * Every run is kept exactly as read; which of them are LAYOUT is decided per element, once
     * that element is complete, by {@code separateLayoutFromContent}.
     *
     * @param reader the stream reader positioned before the document
     * @return the root element, or {@code null} for an empty document
     * @throws XMLStreamException when the stream is not well-formed XML
     * @throws MergeRulesFormatException when the document nests deeper than
     *             {@link #MAX_ELEMENT_DEPTH}
     */
    private static Element readTree(XMLStreamReader reader)
        throws XMLStreamException, MergeRulesFormatException
    {
        Element root = null;
        Deque<Element> stack = new ArrayDeque<>();
        StringBuilder pending = new StringBuilder();
        while (reader.hasNext())
        {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT)
            {
                // Whatever has been read so far belongs to the element still open above.
                flushText(stack.peek(), pending);
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
                    root = element;
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
            else if (event == XMLStreamConstants.END_ELEMENT)
            {
                flushText(stack.peek(), pending);
                separateLayoutFromContent(stack.pop());
            }
        }
        return root;
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
     * for it other than {@code xml:space="preserve"}, which this codec cannot honour because it
     * does not model namespace prefixes (stated on the class) and this format declares none. The
     * merge-settings format has no such element; one that had would need the prefix modelled first.
     *
     * @param element the element whose children are complete
     */
    private static void separateLayoutFromContent(Element element)
    {
        boolean hasElementChild = false;
        boolean hasContent = false;
        for (Element child : element.children())
        {
            if (child.isText())
            {
                hasContent |= !child.textValue().isBlank();
            }
            else
            {
                hasElementChild = true;
            }
        }
        if (!hasElementChild)
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
                if (child.isText())
                {
                    out.append(escapeCharacterData(child.textValue()));
                }
                else
                {
                    writeElement(out, child, depth + 1, true);
                }
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
            writeElement(out, child, depth + 1, false);
        }
        indent(out, depth);
        closeTag(out, element, inline);
    }

    /**
     * Whether these children are mixed content - character data AND child elements side by side.
     *
     * @param content one element's children
     * @return {@code true} when both kinds are present
     */
    private static boolean isMixed(List<Element> content)
    {
        boolean text = false;
        boolean elements = false;
        for (Element child : content)
        {
            text |= child.isText();
            elements |= !child.isText();
        }
        return text && elements;
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
