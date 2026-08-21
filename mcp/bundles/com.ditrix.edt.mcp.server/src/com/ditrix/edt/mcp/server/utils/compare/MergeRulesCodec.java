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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * in whitespace - and for a file already in this layout, not at all. Whitespace itself carries no
 * meaning here: the platform's reader is a StAX pull parser that keys on tags and attributes.
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
        writeElement(out, document.settings(), 0);
        return out.toString();
    }

    /**
     * Writes a document as UTF-8 xml, creating the parent directories when needed and REPLACING
     * an existing file.
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
     * @param file the target file
     * @param document the document
     * @throws IOException when the file cannot be written
     */
    public static void write(Path file, MergeRulesDocument document) throws IOException
    {
        Path target = followSymbolicLink(file.toAbsolutePath());
        Path parent = target.getParent();
        if (parent == null)
        {
            throw new IOException("Cannot write merge rules to '" + file //$NON-NLS-1$
                + "': it names a filesystem root, not a file."); //$NON-NLS-1$
        }
        Files.createDirectories(parent);
        // Created in the TARGET's directory, never in the system temp area: the move over the
        // target has to stay within one filesystem to be atomic. The name carries the target's
        // own name so a leftover is traceable to the write that left it.
        Path temporary = Files.createTempFile(parent, target.getFileName().toString() + '.',
            ".tmp"); //$NON-NLS-1$
        // The temporary lives in the CALLER's directory, so a failure that leaves it behind leaves
        // litter in a place the caller owns. Every failing exit removes it; the successful one does
        // not need to, because the move consumed it.
        try
        {
            Files.write(temporary, serialize(document).getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException suppressed)
            {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * The file the bytes must actually land on: the target itself, or - when the target is a
     * symbolic link - the file that link names.
     * <p>
     * A DANGLING link is resolved through its own recorded destination rather than through the
     * filesystem, which cannot answer for a file that is not there: the write then creates the
     * file the link points at, which is what following the link means, instead of turning the link
     * into a regular file.
     *
     * @param file an absolute path
     * @return the path to write, never {@code null}
     * @throws IOException when the link cannot be read
     */
    private static Path followSymbolicLink(Path file) throws IOException
    {
        if (!Files.isSymbolicLink(file))
        {
            return file;
        }
        try
        {
            return file.toRealPath();
        }
        catch (IOException e)
        {
            Path linkTarget = Files.readSymbolicLink(file);
            Path base = file.getParent();
            return linkTarget.isAbsolute() || base == null ? linkTarget.toAbsolutePath()
                : base.resolve(linkTarget).toAbsolutePath().normalize();
        }
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
     * among the child elements.
     * <p>
     * The runs are accumulated in one buffer, but that buffer is FLUSHED INTO THE ELEMENT THAT
     * OWNS IT at every element boundary, which is the difference that matters: a single buffer
     * merely cleared at each boundary loses the run that precedes a child element and re-attaches
     * the run that follows one to the parent as a whole - so a section with mixed content came
     * back with its leading text deleted and its trailing text moved in front of every child. The
     * codec's promise is that a payload block it does not interpret survives a rewrite verbatim,
     * and mixed content is precisely where a payload block puts its text.
     *
     * @param reader the stream reader positioned before the document
     * @return the root element, or {@code null} for an empty document
     * @throws XMLStreamException when the stream is not well-formed XML
     */
    private static Element readTree(XMLStreamReader reader) throws XMLStreamException
    {
        Element root = null;
        Deque<Element> stack = new ArrayDeque<>();
        StringBuilder pending = new StringBuilder();
        while (reader.hasNext())
        {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT)
            {
                // Whatever has been read so far belongs to the element still open above, and it is
                // followed by a child - so it is interior text, never the element's whole value.
                flushText(stack.peek(), pending, false);
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
                flushText(stack.peek(), pending, true);
                stack.pop();
            }
        }
        return root;
    }

    /**
     * Attaches the character data read so far to the element that owns it.
     * <p>
     * Two kinds of run are told apart, because they are not the same thing:
     * <ul>
     * <li>the element's WHOLE content ({@code closing} and no child seen yet) is its value and is
     * kept byte for byte - a {@code Properties} entry's value is data, and even a blank one is,
     * whereas a blank STRUCTURAL element is just how somebody laid the file out;</li>
     * <li>a run sitting between child elements is mixed content: its meaningful part is kept as a
     * text node in document order and the layout whitespace around it is dropped, exactly as the
     * indentation between two child elements is. Keeping that whitespace instead would make the
     * canonical re-emit differ from a file already in the canonical layout.</li>
     * </ul>
     *
     * @param owner the element the run belongs to, or {@code null} for text outside the root
     * @param pending the accumulated run, cleared by this call
     * @param closing whether the run ends at the owner's own end tag
     */
    private static void flushText(Element owner, StringBuilder pending, boolean closing)
    {
        if (pending.length() == 0)
        {
            return;
        }
        String content = pending.toString();
        pending.setLength(0);
        if (owner == null)
        {
            return;
        }
        if (closing && owner.children().isEmpty())
        {
            if (!content.isBlank() || !isStructural(owner))
            {
                owner.children().add(Element.text(content));
            }
            return;
        }
        String interior = content.strip();
        if (!interior.isEmpty())
        {
            owner.children().add(Element.text(interior));
        }
    }

    private static boolean isStructural(Element element)
    {
        return MergeRulesDocument.TAG_NODE.equals(element.tag())
            || MergeRulesDocument.TAG_MERGE_SETTINGS.equals(element.tag())
            || MergeRulesDocument.TAG_SETTINGS.equals(element.tag());
    }

    private static void writeElement(StringBuilder out, Element element, int depth)
    {
        indent(out, depth);
        out.append('<').append(element.tag());
        for (Map.Entry<String, String> attribute : element.attributes().entrySet())
        {
            out.append(' ').append(attribute.getKey()).append("=\"") //$NON-NLS-1$
                .append(escapeAttribute(attribute.getValue())).append('"');
        }
        List<Element> content = element.children();
        if (content.isEmpty())
        {
            out.append("/>").append(NEW_LINE); //$NON-NLS-1$
            return;
        }
        out.append('>');
        Element only = content.size() == 1 ? content.get(0) : null;
        if (only != null && only.isText())
        {
            // The element's whole content is its value: it goes back on the one line it came from.
            out.append(escapeText(only.textValue())).append("</").append(element.tag()).append('>') //$NON-NLS-1$
                .append(NEW_LINE);
            return;
        }
        out.append(NEW_LINE);
        for (Element child : content)
        {
            if (child.isText())
            {
                indent(out, depth + 1);
                out.append(escapeText(child.textValue())).append(NEW_LINE);
            }
            else
            {
                writeElement(out, child, depth + 1);
            }
        }
        indent(out, depth);
        out.append("</").append(element.tag()).append('>').append(NEW_LINE); //$NON-NLS-1$
    }

    private static void indent(StringBuilder out, int depth)
    {
        for (int i = 0; i < depth; i++)
        {
            out.append(INDENT);
        }
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
