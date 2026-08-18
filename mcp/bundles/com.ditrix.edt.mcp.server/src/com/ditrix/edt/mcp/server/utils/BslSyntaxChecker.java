/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight BSL syntax checker that validates balanced block keywords.
 * Checks: Procedure/EndProcedure, Function/EndFunction, If/EndIf,
 * While/EndDo, For/EndDo, Try/EndTry.
 * <p>
 * Both BSL dialects are recognized, case-insensitively, and neither is the
 * default: every keyword is registered with its Russian and its English
 * spelling side by side in {@link #KEYWORDS}, so a module may even mix them.
 * <p>
 * Keywords are matched ANYWHERE on a line and as many times as they occur, not
 * just at its start, because BSL lets a block open and close on one physical
 * line - \u0415\u0441\u043B\u0438 \u041E\u0442\u043A\u0430\u0437 \u0422\u043E\u0433\u0434\u0430 \u0412\u043E\u0437\u0432\u0440\u0430\u0442; \u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438; is a whole If block, and For each
 * ... Do ... EndDo; is a whole loop. Matching only the first keyword of a line
 * counted such a line as an unclosed block and failed the check on correct code.
 * <p>
 * The check is a WRITE GATE, so both error directions cost something: a false
 * positive blocks a legitimate write, a false negative lets a broken module
 * through. Text that only looks like a keyword is therefore excluded three ways
 * - string literals and comments are masked out ({@link #maskStringLiterals}),
 * preprocessor directives are skipped ({@link #isPreprocessorDirective}), and a
 * member name after a dot is ignored ({@link #isMemberName}).
 */
public final class BslSyntaxChecker
{
    private BslSyntaxChecker()
    {
        // utility class
    }

    private static final String TAG_PROCEDURE = "PROCEDURE"; //$NON-NLS-1$
    private static final String TAG_FUNCTION = "FUNCTION"; //$NON-NLS-1$
    private static final String TAG_IF = "IF"; //$NON-NLS-1$
    private static final String TAG_LOOP = "LOOP"; //$NON-NLS-1$
    private static final String TAG_TRY = "TRY"; //$NON-NLS-1$

    /**
     * Rejects a keyword that is really part of a longer word: a letter, digit or
     * underscore on either side means we are inside an identifier - \u041C\u043E\u0439\u0415\u0441\u043B\u0438,
     * \u0415\u0441\u043B\u0438\u041D\u0443\u0436\u043D\u043E, and also \u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438, whose trailing \u0415\u0441\u043B\u0438 can never be read as an
     * opener because a letter precedes it.
     */
    private static final String LEFT_BOUNDARY = "(?<![\\p{L}\\p{N}_])"; //$NON-NLS-1$

    private static final String RIGHT_BOUNDARY = "(?![\\p{L}\\p{N}_])"; //$NON-NLS-1$

    /** Every recognized spelling, lower-cased, mapped to what it does to the stack. */
    private static final Map<String, BlockKeyword> KEYWORDS = new HashMap<>();

    /** One alternation over all spellings in {@link #KEYWORDS}, in registration order. */
    private static final Pattern BLOCK_KEYWORD;

    static
    {
        List<String> spellings = new ArrayList<>();

        // Closing keywords are registered first so that the alternation, which is ordered,
        // offers the longer spelling before the shorter one it ends with.
        register(spellings, KeywordKind.CLOSE, TAG_PROCEDURE, "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B/EndProcedure", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.CLOSE, TAG_FUNCTION, "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438/EndFunction", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.CLOSE, TAG_IF, "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438/EndIf", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438", "EndIf"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.CLOSE, TAG_LOOP, "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430/EndDo", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430", "EndDo"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.CLOSE, TAG_TRY, "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438/EndTry", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438", "EndTry"); //$NON-NLS-1$ //$NON-NLS-2$

        // ElsIf continues the enclosing If instead of opening a new one. The boundary above
        // already stops the If it ends with from matching inside it, so this entry changes no
        // outcome today; it is registered to state the intent and to keep it true if the
        // boundary is ever relaxed.
        register(spellings, KeywordKind.NEUTRAL, TAG_IF, null,
            "\u0418\u043D\u0430\u0447\u0435\u0415\u0441\u043B\u0438", "ElsIf", "ElseIf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Opening keywords. An \u0410\u0441\u0438\u043D\u0445/Async prefix needs no rule of its own now that
        // matching is not anchored to the line start - \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430/Function is found
        // wherever it sits. \u0416\u0434\u0430\u0442\u044C/Await is an expression keyword and opens no block,
        // so it is deliberately absent.
        register(spellings, KeywordKind.OPEN, TAG_PROCEDURE, null,
            "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430", "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.OPEN, TAG_FUNCTION, null,
            "\u0424\u0443\u043D\u043A\u0446\u0438\u044F", "Function"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.OPEN, TAG_IF, null,
            "\u0415\u0441\u043B\u0438", "If"); //$NON-NLS-1$ //$NON-NLS-2$
        register(spellings, KeywordKind.OPEN, TAG_LOOP, null,
            "\u041F\u043E\u043A\u0430", "While", "\u0414\u043B\u044F", "For"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        register(spellings, KeywordKind.OPEN, TAG_TRY, null,
            "\u041F\u043E\u043F\u044B\u0442\u043A\u0430", "Try"); //$NON-NLS-1$ //$NON-NLS-2$

        BLOCK_KEYWORD = Pattern.compile(
            LEFT_BOUNDARY + "(" + String.join("|", spellings) + ")" + RIGHT_BOUNDARY, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Adds every spelling of one keyword to the alternation and to the lookup map.
     *
     * @param spellings the alternation being built, in order; appended to
     * @param kind what the keyword does to the open-block stack
     * @param tag the block tag the keyword opens or closes
     * @param display the keyword as it should read in an error message, or
     *     {@code null} for a kind that never reports one
     * @param words the spellings, one per dialect
     */
    private static void register(List<String> spellings, KeywordKind kind, String tag, String display,
        String... words)
    {
        BlockKeyword keyword = new BlockKeyword(kind, tag, display);
        for (String word : words)
        {
            spellings.add(word);
            KEYWORDS.put(word.toLowerCase(Locale.ROOT), keyword);
        }
    }

    /** What a matched keyword does to the open-block stack. */
    private enum KeywordKind
    {
        /** Opens a block - pushed onto the stack. */
        OPEN,
        /** Closes a block - pops the stack, reporting any mismatch. */
        CLOSE,
        /** Neither, but still consumed so a keyword it contains cannot match inside it. */
        NEUTRAL
    }

    /** A recognized block keyword: what it does, to which block, and how it reads in an error. */
    private static final class BlockKeyword
    {
        private final KeywordKind kind;
        private final String tag;
        private final String display;

        private BlockKeyword(KeywordKind kind, String tag, String display)
        {
            this.kind = kind;
            this.tag = tag;
            this.display = display;
        }
    }

    /**
     * Result of a BSL syntax check.
     */
    public static class CheckResult
    {
        private final boolean valid;
        private final List<String> errors;

        public CheckResult(boolean valid, List<String> errors)
        {
            this.valid = valid;
            this.errors = errors;
        }

        public boolean isValid()
        {
            return valid;
        }

        public List<String> getErrors()
        {
            return errors;
        }
    }

    /**
     * Checks the given BSL source lines for balanced block keywords.
     *
     * @param lines the source lines to check
     * @return check result with validity flag and error messages
     */
    public static CheckResult check(List<String> lines)
    {
        List<String> errors = new ArrayList<>();
        // Stack of (tag, lineNumber as string)
        Deque<String[]> stack = new ArrayDeque<>();
        // Carries whether a string literal is still open across physical lines
        StringLiteralState stringState = new StringLiteralState();
        // Carries a member-access dot left dangling at the end of the previous line
        boolean danglingDot = false;

        for (int i = 0; i < lines.size(); i++)
        {
            int lineNum = i + 1;

            // A double-quote string only continues onto the next physical line via a leading '|'
            // continuation. If the previous line ended still inside a string but THIS line is not a
            // continuation, the literal was not a valid multi-line string (a mis-tracked quote or a
            // genuinely unclosed string) - reset so it does not mask the real code that follows,
            // which would hide real block keywords (and their imbalance).
            if (stringState.insideString && !lines.get(i).trim().startsWith("|")) //$NON-NLS-1$
            {
                stringState.insideString = false;
            }

            String code = preprocessLine(lines.get(i), stringState);
            if (code == null)
            {
                // A blank, comment-only or string-continuation line does not interrupt a member
                // access split across lines, so danglingDot is deliberately left as it was.
                continue;
            }
            if (isPreprocessorDirective(code))
            {
                // A directive DOES interrupt it: an expression cannot legally continue across a
                // branch boundary, and carrying the dot over would let a dot left dangling in one
                // branch silence a real block keyword that follows in another.
                danglingDot = false;
                continue;
            }

            scanLine(code, lineNum, stack, errors, danglingDot);
            danglingDot = endsWithMemberDot(code);
        }

        reportUnclosedBlocks(stack, errors);

        return new CheckResult(errors.isEmpty(), errors);
    }

    /**
     * Applies every block keyword on one line to the stack, left to right.
     * <p>
     * Walking the whole line is what makes the single-line forms balance: the
     * opener and the closer of \u0415\u0441\u043B\u0438 ... \u0422\u043E\u0433\u0434\u0430 ... \u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438; are both seen, in
     * the order they are written, and so are several blocks on one line.
     *
     * @param code the line with string literals and comments already masked out
     * @param lineNum the 1-based line number
     * @param stack the open-block stack
     * @param errors the accumulated error messages
     * @param danglingDot whether the previous line ended on a member-access dot
     */
    private static void scanLine(String code, int lineNum, Deque<String[]> stack, List<String> errors,
        boolean danglingDot)
    {
        Matcher matcher = BLOCK_KEYWORD.matcher(code);
        while (matcher.find())
        {
            if (isMemberName(code, matcher.start(), danglingDot))
            {
                continue;
            }
            BlockKeyword keyword = KEYWORDS.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (keyword == null)
            {
                continue;
            }
            if (keyword.kind == KeywordKind.OPEN)
            {
                stack.push(new String[] { keyword.tag, String.valueOf(lineNum) });
            }
            else if (keyword.kind == KeywordKind.CLOSE)
            {
                popAndCheck(stack, keyword.tag, keyword.display, lineNum, errors);
            }
        }
    }

    /**
     * Tells whether a whole line is a preprocessor directive, which is skipped.
     * <p>
     * The BSL lexer spells the prefix {@code '#' (' '|'\t')*}, so the directive
     * keyword may be separated from the {@code #} by spaces or tabs - matching
     * only the glued {@code #\u0415\u0441\u043B\u0438} form would read a spaced {@code # \u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438}
     * as a runtime block closer and let it pop a real If.
     * <p>
     * Every directive is skipped, not just the conditional: #\u041E\u0431\u043B\u0430\u0441\u0442\u044C/#Region and
     * the extension directives #\u0412\u0441\u0442\u0430\u0432\u043A\u0430/#\u0423\u0434\u0430\u043B\u0435\u043D\u0438\u0435 open no runtime block either.
     * <p>
     * Scanning every arm of a conditional as one stream - rather than once per
     * selected variant - is sound against EDT's grammar. An arm holds complete
     * {@code Statement SEMICOLON} items and may only end with a nested
     * preprocessor statement; the method- and module-level forms likewise hold
     * complete declarations, methods and statements, and the inner and
     * {@code ...After} forms continue with complete statements too. No arm can
     * therefore hold just part of an If/While/For/Try or of a Method: each one
     * balances on its own, so their concatenation balances exactly when every
     * variant does, and a per-variant analysis would return the same verdict.
     * <p>
     * That reasoning is scoped to what EDT's grammar accepts. Whether some 1C
     * platform version tolerates a runtime block split across a conditional
     * boundary is NOT established here; such source is outside this check's
     * guarantee, and on it the one-stream scan may balance where no variant
     * does. This is a block-balance gate, not a parser.
     *
     * @param code the masked line
     * @return true if the first non-whitespace character is {@code #}
     */
    private static boolean isPreprocessorDirective(String code)
    {
        for (int i = 0; i < code.length(); i++)
        {
            char c = code.charAt(i);
            if (!Character.isWhitespace(c))
            {
                return c == '#';
            }
        }
        return false;
    }

    /**
     * Tells whether the keyword found at {@code start} is a member name rather than a
     * block keyword - that is, whether the nearest non-whitespace character before it
     * is a member-access dot.
     * <p>
     * BSL allows a reserved word as a member name: the grammar's ExtName rule lists
     * every keyword this class matches, closing ones included, so both \u041E\u0431\u044A\u0435\u043A\u0442.\u0424\u0443\u043D\u043A\u0446\u0438\u044F
     * and \u041E\u0431\u044A\u0435\u043A\u0442.\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438 are legal member accesses. Whitespace - including a line
     * break, which arrives here as {@code danglingDot} - is allowed after the dot.
     *
     * @param code the masked line
     * @param start the index the keyword starts at
     * @param danglingDot whether the previous line ended on a member-access dot
     * @return true if the keyword is a member name and must not touch the stack
     */
    private static boolean isMemberName(String code, int start, boolean danglingDot)
    {
        for (int i = start - 1; i >= 0; i--)
        {
            char c = code.charAt(i);
            if (!Character.isWhitespace(c))
            {
                return c == '.';
            }
        }
        return danglingDot;
    }

    /**
     * Tells whether a line ends on a member-access dot, so that a keyword opening the
     * next line is a member name rather than a block keyword.
     *
     * @param code the masked line
     * @return true if the last non-whitespace character is a dot
     */
    private static boolean endsWithMemberDot(String code)
    {
        for (int i = code.length() - 1; i >= 0; i--)
        {
            char c = code.charAt(i);
            if (!Character.isWhitespace(c))
            {
                return c == '.';
            }
        }
        return false;
    }

    /**
     * Carries whether a string literal opened on an earlier physical line is still
     * open when the next line starts. BSL string literals can span several lines,
     * each continuation line starting with a leading {@code |}.
     */
    private static final class StringLiteralState
    {
        private boolean insideString;
    }

    /**
     * Normalize a source line for keyword matching by masking any string-literal
     * (and comment) content via {@link #maskStringLiterals}.
     *
     * @param line the raw source line
     * @param state carries whether a string literal is already open when this
     *     line starts; updated in place to reflect the state after this line
     * @return the masked line ready for keyword matching, or {@code null} if there
     *     is no real code left on the line (blank, a full comment, or a pure
     *     string continuation line)
     */
    private static String preprocessLine(String line, StringLiteralState state)
    {
        String masked = maskStringLiterals(line, state);
        return masked.trim().isEmpty() ? null : masked;
    }

    /**
     * Masks the parts of a line that are lexically inside a string literal so that
     * keyword-looking text embedded in query or message text can never match a
     * block keyword, while any real code on the same physical line - including
     * code that follows the string's closing quote - is left untouched.
     * <p>
     * Walks the line character by character, toggling {@code state.insideString}
     * on every double quote, EXCEPT a doubled {@code ""} which is an escaped quote
     * inside the literal and does not close it. Masked characters are replaced
     * with a space (not removed), so the column position of any real code after
     * the string is preserved. An inline {@code //} comment is only recognized
     * while NOT inside a string, so a {@code //} inside a URL or message text no
     * longer truncates the line. {@code state.insideString} is updated in place so
     * the flag carries across lines for a literal that spans several physical
     * lines (each continuation masks to blank and is effectively skipped, same
     * outcome as before, but now for the correct reason).
     *
     * @param line the raw source line
     * @param state carries whether a string literal is already open when this
     *     line starts; updated in place to reflect the state after this line
     * @return the line with string-literal content (and any trailing comment)
     *     replaced by spaces or dropped
     */
    private static String maskStringLiterals(String line, StringLiteralState state)
    {
        int len = line.length();
        StringBuilder masked = new StringBuilder(len);
        boolean insideString = state.insideString; // double-quote "..." string, CARRIED across lines
        boolean insideDate = false;                 // single-quote '...' date literal, intra-line only

        for (int i = 0; i < len; i++)
        {
            char c = line.charAt(i);
            if (insideString)
            {
                if (c == '"' && i + 1 < len && line.charAt(i + 1) == '"')
                {
                    // Doubled quote: an escaped quote inside the literal, string stays open
                    masked.append(' ').append(' ');
                    i++;
                    continue;
                }
                if (c == '"')
                {
                    insideString = false;
                }
                masked.append(' ');
                continue;
            }
            if (insideDate)
            {
                // A single-quote '...' date literal. A double-quote inside it is CONTENT, not a
                // string toggle - masking it here is what stops a stray '"' inside a date/'...'
                // token from flipping insideString and swallowing the rest of the module.
                if (c == '\'')
                {
                    insideDate = false;
                }
                masked.append(' ');
                continue;
            }
            if (c == '"')
            {
                insideString = true;
                masked.append(' ');
                continue;
            }
            if (c == '\'')
            {
                insideDate = true;
                masked.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < len && line.charAt(i + 1) == '/')
            {
                break; // inline comment - the rest of the line is not code
            }
            masked.append(c);
        }

        // A single-quote date literal never spans physical lines, so insideDate is intentionally
        // NOT persisted; only the double-quote string carries (via the '|'-continuation rule the
        // caller applies before the next line - see check()).
        state.insideString = insideString;
        return masked.toString();
    }

    /**
     * Drain the open-block stack, appending an "unclosed" error for each remaining block.
     *
     * @param stack the open-block stack
     * @param errors the accumulated error messages
     */
    private static void reportUnclosedBlocks(Deque<String[]> stack, List<String> errors)
    {
        while (!stack.isEmpty())
        {
            String[] entry = stack.pop();
            errors.add("Unclosed " + tagToKeyword(entry[0]) + " from line " + entry[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void popAndCheck(Deque<String[]> stack, String expectedTag,
        String keyword, int lineNum, List<String> errors)
    {
        if (stack.isEmpty())
        {
            errors.add("Unexpected " + keyword + " at line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + " (no matching opening keyword)"); //$NON-NLS-1$
            return;
        }
        String[] top = stack.pop();
        if (!top[0].equals(expectedTag))
        {
            errors.add("Mismatched " + keyword + " at line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + ", expected closing for " + tagToKeyword(top[0]) //$NON-NLS-1$
                + " from line " + top[1]); //$NON-NLS-1$
        }
    }

    private static String tagToKeyword(String tag)
    {
        switch (tag)
        {
            case TAG_PROCEDURE:
                return "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430/Procedure"; //$NON-NLS-1$
            case TAG_FUNCTION:
                return "\u0424\u0443\u043D\u043A\u0446\u0438\u044F/Function"; //$NON-NLS-1$
            case TAG_IF:
                return "\u0415\u0441\u043B\u0438/If"; //$NON-NLS-1$
            case TAG_LOOP:
                return "\u041F\u043E\u043A\u0430|\u0414\u043B\u044F/While|For"; //$NON-NLS-1$
            case TAG_TRY:
                return "\u041F\u043E\u043F\u044B\u0442\u043A\u0430/Try"; //$NON-NLS-1$
            default:
                return tag;
        }
    }
}
