/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procedures and functions a BSL module declares more than once under one name (#987), read from the TEXT
 * with the shared whole-line scanner ({@link BslModuleUtils#findMethodSpansViaText}) - no EDT model, so it
 * runs on any thread and before anything is written.
 * <p>
 * The 1C platform refuses to load a module that compiles two procedures or functions of one name - the
 * whole module stops loading - and EDT marks the second one ({@code method-already-defined}, its
 * {@code BslJavaValidator.checkMethodNamesAreUnique}). EDT judges by the methods' compilation environments;
 * this text reader cannot evaluate a preprocessor condition, so it tells three cases apart by STRUCTURE only:
 * <ul>
 * <li>{@link Kind#EXCLUSIVE} - the declarations sit in different arms of one {@code #If}/{@code #Если} block,
 * so the preprocessor never keeps both;</li>
 * <li>{@link Kind#DUPLICATE} - they share a preprocessor context (one's branch path is the other's or lies
 * inside it) and carry the same compilation directives, so wherever the deeper one is compiled the other is
 * compiled with it: a certain duplicate;</li>
 * <li>{@link Kind#POSSIBLE} - anything else: separate {@code #If} blocks, or different compilation directives
 * ({@code &AtClient} / {@code &AtServer}); whether both are compiled together depends on what the conditions
 * and directives mean, which a text scan cannot decide.</li>
 * </ul>
 * Names are compared ignoring case, the platform's and the scanner's rule; keywords and directives are read in
 * both dialects. A declaration the scanner cannot address (see {@link BslModuleUtils#unaddressable}) is not
 * seen here either.
 * </p>
 */
public final class BslDuplicateMethods
{
    /** How two or more declarations of one name relate, worst first. */
    public enum Kind
    {
        /** Compiled together wherever compiled at all: the module will not load. */
        DUPLICATE,
        /** May be compiled together, depending on the preprocessor conditions or compilation directives. */
        POSSIBLE,
        /** In different arms of one #If block: never compiled together. */
        EXCLUSIVE
    }

    /** One declaration of a repeated name. Lines are 1-based. */
    public static final class Declaration
    {
        /** The line of the Procedure/Function keyword. */
        public final int line;
        /** The first line of the method's span, its documentation and annotations included. */
        public final int startLine;
        /** The last line of the method's span. */
        public final int endLine;

        Declaration(int line, int startLine, int endLine)
        {
            this.line = line;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        /** @return {@code "start-end"} */
        public String range()
        {
            return startLine + "-" + endLine; //$NON-NLS-1$
        }
    }

    /** A name declared more than once, with every declaration in source order. */
    public static final class Group
    {
        /** The name as the first declaration spells it. */
        public final String name;
        /** Every declaration, in source order. */
        public final List<Declaration> declarations;
        /** The worst relation between any two of them. */
        public final Kind kind;
        /**
         * For {@link Kind#EXCLUSIVE}: the 1-based opening lines of the #If blocks whose arms separate the pairs, in
         * ascending order - more than one when the declarations sit at different depths (two in the arms of an
         * inner block, a third in the outer block's #Else); else empty.
         */
        public final List<Integer> branchBlockLines;

        Group(String name, List<Declaration> declarations, Kind kind, List<Integer> branchBlockLines)
        {
            this.name = name;
            this.declarations = declarations;
            this.kind = kind;
            this.branchBlockLines = branchBlockLines;
        }

        /** @return the declaration lines, e.g. {@code "12, 300"} */
        public String lines()
        {
            StringBuilder sb = new StringBuilder();
            for (Declaration declaration : declarations)
            {
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(declaration.line);
            }
            return sb.toString();
        }

        /**
         * One sentence on what the relation means, for a read tool's notice.
         *
         * @return the sentence, ending with a period
         */
        public String meaning()
        {
            switch (kind)
            {
                case DUPLICATE:
                    return "They share one preprocessor context, so the 1C platform will refuse to load the module " //$NON-NLS-1$
                        + "(EDT marks it method-already-defined): rename or delete one."; //$NON-NLS-1$
                case EXCLUSIVE:
                    return branchBlockLines.size() == 1
                        ? "They sit in different branches of the #If block opened at line " + branchBlockLines.get(0) //$NON-NLS-1$
                            + ", so only one of them is compiled." //$NON-NLS-1$
                        : "They sit in mutually exclusive branches of the #If blocks opened at lines " //$NON-NLS-1$
                            + joined(branchBlockLines) + ", so only one of them is compiled."; //$NON-NLS-1$
                default:
                    return "They sit in separate #If blocks or carry different compilation directives; whether both " //$NON-NLS-1$
                        + "are compiled together depends on those conditions."; //$NON-NLS-1$
            }
        }
    }

    /**
     * The compilation directives of a form or command module, both dialects, mapped to one canonical spelling.
     * Any other annotation (an interception {@code &Before("X")} and the like) does not decide where a method
     * is compiled and is not compared.
     */
    private static final Map<String, String> DIRECTIVES = Map.ofEntries(
        Map.entry("atclient", "AtClient"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("наклиенте", "AtClient"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("atserver", "AtServer"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("насервере", "AtServer"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("atservernocontext", "AtServerNoContext"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("насерверебезконтекста", //$NON-NLS-1$
            "AtServerNoContext"), //$NON-NLS-1$
        Map.entry("atclientatservernocontext", "AtClientAtServerNoContext"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("наклиентенасерверебезконтекста", //$NON-NLS-1$
            "AtClientAtServerNoContext"), //$NON-NLS-1$
        Map.entry("atclientatserver", "AtClientAtServer"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("наклиентенасервере", //$NON-NLS-1$
            "AtClientAtServer")); //$NON-NLS-1$

    /** An annotation word on a (masked) preamble line. */
    private static final Pattern ANNOTATION = Pattern.compile("&\\s*([\\p{L}_][\\p{L}\\p{N}_]*)"); //$NON-NLS-1$

    private BslDuplicateMethods()
    {
        // utility class
    }

    /**
     * Every name {@code lines} declares more than once, in the order of each name's first declaration.
     *
     * @param lines the module lines, never {@code null}
     * @return the repeated names; empty when every name is declared once
     */
    public static List<Group> find(List<String> lines)
    {
        List<String> masked = BslSyntaxChecker.maskLiteralsAndComments(lines);
        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(lines, masked);
        Map<String, List<BslModuleUtils.MethodSpan>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> order = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : spans)
        {
            List<BslModuleUtils.MethodSpan> same = byName.get(span.name);
            if (same == null)
            {
                same = new ArrayList<>();
                byName.put(span.name, same);
                order.add(span.name);
            }
            same.add(span);
        }
        List<Group> groups = new ArrayList<>();
        int[][] branches = null;
        for (String name : order)
        {
            List<BslModuleUtils.MethodSpan> same = byName.get(name);
            if (same.size() < 2)
            {
                continue;
            }
            if (branches == null)
            {
                branches = BslSyntaxChecker.conditionalBranches(lines);
            }
            groups.add(group(name, same, branches, masked));
        }
        return groups;
    }

    private static Group group(String name, List<BslModuleUtils.MethodSpan> same, int[][] branches,
        List<String> masked)
    {
        Kind worst = Kind.EXCLUSIVE;
        Set<Integer> blockLines = new TreeSet<>();
        for (int i = 0; i < same.size(); i++)
        {
            for (int j = i + 1; j < same.size(); j++)
            {
                BslModuleUtils.MethodSpan a = same.get(i);
                BslModuleUtils.MethodSpan b = same.get(j);
                int separating = separatingBlock(branches[a.declarationLine], branches[b.declarationLine]);
                Kind kind;
                if (separating > 0)
                {
                    kind = Kind.EXCLUSIVE;
                    blockLines.add(separating);
                }
                else if (separating == 0 && directives(masked, a).equals(directives(masked, b)))
                {
                    kind = Kind.DUPLICATE;
                }
                else
                {
                    kind = Kind.POSSIBLE;
                }
                if (kind.ordinal() < worst.ordinal())
                {
                    worst = kind;
                }
            }
        }
        List<Declaration> declarations = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : same)
        {
            declarations.add(new Declaration(span.declarationLine + 1, span.startLine + 1, span.endLine + 1));
        }
        return new Group(same.get(0).name, declarations, worst,
            worst == Kind.EXCLUSIVE ? List.copyOf(blockLines) : List.of());
    }

    /**
     * How two branch paths relate: the opening line of the #If block they reach through different arms (the
     * declarations exclude each other), {@code 0} when one path is the other's or lies inside it (one
     * preprocessor context), or {@code -1} when they part into separate blocks.
     */
    static int separatingBlock(int[] a, int[] b)
    {
        int common = Math.min(a.length, b.length);
        for (int at = 0; at < common; at += 2)
        {
            if (a[at] != b[at])
            {
                return -1;
            }
            if (a[at + 1] != b[at + 1])
            {
                return a[at];
            }
        }
        return 0;
    }

    /** The canonical compilation directives in a method's preamble (masked lines, so no comment counts). */
    private static Set<String> directives(List<String> masked, BslModuleUtils.MethodSpan span)
    {
        Set<String> found = new TreeSet<>();
        for (int line = span.startLine; line < span.declarationLine; line++)
        {
            Matcher matcher = ANNOTATION.matcher(masked.get(line));
            while (matcher.find())
            {
                String canonical = DIRECTIVES.get(matcher.group(1).toLowerCase(Locale.ROOT));
                if (canonical != null)
                {
                    found.add(canonical);
                }
            }
        }
        return found;
    }

    /**
     * What a write does to the repeated names (#987): compares the module before it with the text it would
     * write.
     */
    public static final class WriteVerdict
    {
        /** Certain duplicates the write CREATES: a name that becomes one, or one with more declarations than before. */
        public final List<Group> introduced;
        /** Possible duplicates the write creates the same way. */
        public final List<Group> possible;
        /** Certain duplicates the module already had, left as they were. */
        public final List<Group> preExisting;
        /** The 1-based declaration lines of each name in the module before the write. */
        private final Map<String, List<Integer>> before;

        WriteVerdict(List<Group> introduced, List<Group> possible, List<Group> preExisting,
            Map<String, List<Integer>> before)
        {
            this.introduced = introduced;
            this.possible = possible;
            this.preExisting = preExisting;
            this.before = before;
        }

        /**
         * The refusal of a write that introduces a certain duplicate.
         *
         * @return the message, or {@code null} when it introduces none
         */
        public String refusal()
        {
            if (introduced.isEmpty())
            {
                return null;
            }
            StringBuilder sb = new StringBuilder("Write blocked: ").append(describe(introduced)) //$NON-NLS-1$
                .append(" The 1C platform refuses to load a module that declares a procedure or function name twice ") //$NON-NLS-1$
                .append("in one preprocessor context - every method of the module stops working - and EDT marks it ") //$NON-NLS-1$
                .append("method-already-defined. Nothing was written. Rename the new method, or change the existing ") //$NON-NLS-1$
                .append("one in place with mode=replaceMethod. Two declarations of one name are allowed only in ") //$NON-NLS-1$
                .append("different branches of one #If block."); //$NON-NLS-1$
            return sb.toString();
        }

        /**
         * The warning a successful write carries: certain duplicates written anyway (only with the check skipped),
         * possible duplicates it creates, and certain duplicates the module already had.
         *
         * @param checkSkipped whether the caller skipped the syntax check, so {@link #introduced} was written
         * @return the warning, or {@code null} when there is nothing to say
         */
        public String warning(boolean checkSkipped)
        {
            List<String> parts = new ArrayList<>();
            if (checkSkipped && !introduced.isEmpty())
            {
                parts.add("skipSyntaxCheck=true wrote a duplicate method name: " + describe(introduced) //$NON-NLS-1$
                    + " The 1C platform will refuse to load this module until one is renamed or deleted."); //$NON-NLS-1$
            }
            if (!possible.isEmpty())
            {
                parts.add("Possible duplicate method name: " + describe(possible) //$NON-NLS-1$
                    + " They sit in separate #If blocks or carry different compilation directives; if both can be " //$NON-NLS-1$
                    + "compiled together, the 1C platform will refuse to load the module."); //$NON-NLS-1$
            }
            if (!preExisting.isEmpty())
            {
                parts.add("The module already declared a method name twice before this write: " //$NON-NLS-1$
                    + describe(preExisting) + " The 1C platform refuses to load it until one is renamed or deleted."); //$NON-NLS-1$
            }
            return parts.isEmpty() ? null : String.join(" ", parts); //$NON-NLS-1$
        }

        private String describe(List<Group> groups)
        {
            StringBuilder sb = new StringBuilder();
            for (Group group : groups)
            {
                sb.append('\'').append(group.name).append("' is declared ").append(group.declarations.size()) //$NON-NLS-1$
                    .append(" times (lines ").append(group.lines()).append(" of the resulting text"); //$NON-NLS-1$ //$NON-NLS-2$
                List<Integer> was = before.get(group.name);
                if (was != null)
                {
                    sb.append("; the current module declares it at line").append(was.size() == 1 ? " " : "s ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .append(joined(was));
                }
                sb.append(")."); //$NON-NLS-1$
                sb.append(' ');
            }
            return sb.toString().trim();
        }
    }

    /**
     * Judges a write: which repeated names of {@code after} it creates and which {@code before} already had.
     * A name counts as created when it is repeated after the write and either was not repeated in the same way
     * before or has more declarations than before - so an unrelated edit of a module that already repeats a
     * name is not blocked, while one more copy of that name is.
     *
     * @param before the module's lines before the write (empty for a new module)
     * @param after the lines the write would produce
     * @return the verdict
     */
    public static WriteVerdict judgeWrite(List<String> before, List<String> after)
    {
        List<Group> afterGroups = find(after);
        if (afterGroups.isEmpty())
        {
            return new WriteVerdict(List.of(), List.of(), List.of(), Map.of());
        }
        Map<String, Group> was = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<Integer>> beforeLines = declarationLines(before);
        if (!beforeLines.isEmpty())
        {
            for (Group group : find(before))
            {
                was.put(group.name, group);
            }
        }
        List<Group> introduced = new ArrayList<>();
        List<Group> possible = new ArrayList<>();
        List<Group> preExisting = new ArrayList<>();
        for (Group group : afterGroups)
        {
            if (group.kind == Kind.EXCLUSIVE)
            {
                continue;
            }
            Group previous = was.get(group.name);
            boolean grew = group.declarations.size() > beforeLines.getOrDefault(group.name, List.of()).size();
            boolean sameOrWorseBefore = previous != null && previous.kind.ordinal() <= group.kind.ordinal();
            if (grew || !sameOrWorseBefore)
            {
                (group.kind == Kind.DUPLICATE ? introduced : possible).add(group);
            }
            else if (group.kind == Kind.DUPLICATE)
            {
                preExisting.add(group);
            }
        }
        return new WriteVerdict(introduced, possible, preExisting, beforeLines);
    }

    /** The 1-based declaration lines of each name {@code lines} declares, ignoring case. */
    private static Map<String, List<Integer>> declarationLines(List<String> lines)
    {
        Map<String, List<Integer>> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (lines.isEmpty())
        {
            return found;
        }
        for (BslModuleUtils.MethodSpan span : BslModuleUtils.findMethodSpansViaText(lines))
        {
            found.computeIfAbsent(span.name, name -> new ArrayList<>()).add(span.declarationLine + 1);
        }
        return found;
    }

    private static String joined(List<Integer> lines)
    {
        StringBuilder sb = new StringBuilder();
        for (Integer line : lines)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * The repeated name {@code name} belongs to, if any.
     *
     * @param groups the groups of {@link #find}
     * @param name a method name
     * @return its group, or {@code null} when it is declared at most once
     */
    public static Group groupOf(List<Group> groups, String name)
    {
        for (Group group : groups)
        {
            if (group.name.equalsIgnoreCase(name))
            {
                return group;
            }
        }
        return null;
    }
}
