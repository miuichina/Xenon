package zxc.iconic.xenon.helpers;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a GitHub Flavored Markdown (GFM) string into an Android
 * {@link CharSequence} with Telegram-native text spans (bold, italic,
 * monospace, strikethrough, links, spoilers).
 *
 * <p>Block-level elements handled here:
 * <ul>
 *   <li>{@code # / ## / ###} headers → {@code **text**} (bold via EntitiesHelper)</li>
 *   <li>{@code - / * / +} unordered list items → {@code • item}</li>
 *   <li>{@code 1. / 2. / …} ordered list items → kept as-is</li>
 *   <li>{@code > blockquote} → text without the {@code >} prefix</li>
 *   <li>{@code ---} / {@code ***} / {@code ___} horizontal rule → {@code ──────────────}</li>
 *   <li>GFM tables → header row bolded, cells separated by {@code │}</li>
 *   <li>HTML comments ({@code <!-- … -->}) → skipped</li>
 * </ul>
 *
 * <p>Inline elements ({@code **bold**}, {@code __italic__}, {@code `code`},
 * {@code ~~strike~~}, {@code ||spoiler||}, {@code [text](url)}) are delegated
 * to {@link EntitiesHelper#parseMarkdown}.
 */
public final class GitHubMarkdownParser {

    private GitHubMarkdownParser() {}

    /**
     * Parse {@code markdown} into a spannable {@link CharSequence}.
     *
     * @param markdown raw GitHub Markdown string (may be {@code null})
     * @return formatted {@link CharSequence}, never {@code null}
     */
    @NonNull
    public static CharSequence parse(@Nullable String markdown) {
        if (TextUtils.isEmpty(markdown)) return "";

        // Normalise line-endings and collapse excessive blank lines.
        String text = markdown.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("\n{3,}", "\n\n");

        String[] lines = text.split("\n", -1);
        StringBuilder sb    = new StringBuilder(text.length());
        boolean needNewline  = false;

        // Buffer for collecting consecutive GFM table rows before rendering.
        List<String> tableBuffer = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line    = lines[i];
            String trimmed = line.trim();

            // --- GFM table row detection ---
            // A table row starts and ends with '|' and has length > 1.
            boolean isTableRow = trimmed.length() > 1
                    && trimmed.charAt(0) == '|'
                    && trimmed.charAt(trimmed.length() - 1) == '|';

            if (isTableRow) {
                tableBuffer.add(trimmed);
                continue; // accumulate; render when the block ends
            }

            // Flush buffered table when a non-table line is reached.
            if (!tableBuffer.isEmpty()) {
                if (needNewline) sb.append('\n');
                flushTable(sb, tableBuffer);
                tableBuffer.clear();
                needNewline = true;
            }

            // --- HTML comments: skip entirely ---
            if (trimmed.startsWith("<!--")) continue;

            // --- Horizontal rule: ---, ***, ___ ---
            if (trimmed.matches("[-*_]{3,}")) {
                if (needNewline) sb.append('\n');
                sb.append("──────────────");
                needNewline = true;
                continue;
            }

            // --- ATX headers: # ## ### (up to ######) ---
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
            if (hashes > 0 && hashes <= 6
                    && hashes < trimmed.length()
                    && trimmed.charAt(hashes) == ' ') {
                // Strip inner ** to avoid producing ****text** when the
                // header itself already contains bold markdown.
                String headerText = trimmed.substring(hashes + 1).trim().replace("**", "");
                if (needNewline) sb.append('\n');
                sb.append("**").append(headerText).append("**");
                needNewline = true;
                continue;
            }

            // --- Unordered list: "- item" / "* item" / "+ item" ---
            // Guard: charAt(1) must be a space so we don't swallow **bold**
            // or --- horizontal rules (already handled above).
            if (trimmed.length() >= 2
                    && (trimmed.charAt(0) == '-'
                        || trimmed.charAt(0) == '*'
                        || trimmed.charAt(0) == '+')
                    && trimmed.charAt(1) == ' ') {
                if (needNewline) sb.append('\n');
                sb.append("• ").append(trimmed.substring(2));
                needNewline = true;
                continue;
            }

            // --- Blockquote: "> text" ---
            if (trimmed.startsWith("> ")) {
                if (needNewline) sb.append('\n');
                sb.append(trimmed.substring(2));
                needNewline = true;
                continue;
            }
            if (trimmed.equals(">")) {
                // empty blockquote line — treat as blank
                continue;
            }

            // --- Regular line (ordered lists, plain text, inline markdown) ---
            if (needNewline) sb.append('\n');
            sb.append(line);
            needNewline = true;
        }

        // Flush any trailing table block.
        if (!tableBuffer.isEmpty()) {
            if (needNewline) sb.append('\n');
            flushTable(sb, tableBuffer);
        }

        // Delegate inline markdown (**bold**, __italic__, `code`,
        // ~~strike~~, ||spoiler||, [text](url)) to the existing helper.
        return EntitiesHelper.parseMarkdown(sb.toString());
    }

    // -------------------------------------------------------------------------
    // Table rendering
    // -------------------------------------------------------------------------

    /**
     * Renders a list of raw GFM table row strings (each starting and ending
     * with {@code |}) into the output {@link StringBuilder}.
     *
     * <p>GFM table structure:
     * <pre>
     * | Header 1 | Header 2 |   ← header row
     * |----------|----------|   ← separator row (required by spec)
     * | Data 1   | Data 2   |   ← data rows
     * </pre>
     *
     * <p>Rendering on mobile:
     * <ul>
     *   <li>Header row → {@code **Cell1 │ Cell2**} (bold via EntitiesHelper)</li>
     *   <li>Separator row → skipped</li>
     *   <li>Data rows → {@code Cell1 │ Cell2}</li>
     * </ul>
     */
    private static void flushTable(@NonNull StringBuilder sb,
                                   @NonNull List<String> rows) {
        // Identify which row index is the separator (all |, -, :, space chars).
        int separatorIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            String stripped = rows.get(i)
                    .replace("|", "")
                    .replace("-", "")
                    .replace(":", "")
                    .replace(" ", "");
            if (stripped.isEmpty()) {
                separatorIdx = i;
                break;
            }
        }

        // Parse each row into trimmed cell arrays.
        // separatorIdx == 1 in a well-formed GFM table; row 0 is the header.
        boolean firstLine = true;
        for (int i = 0; i < rows.size(); i++) {
            if (i == separatorIdx) continue; // skip separator

            String[] cells = parseCells(rows.get(i));
            if (cells.length == 0) continue;

            String joined = joinCells(cells);
            if (!firstLine) sb.append('\n');
            firstLine = false;

            boolean isHeader = separatorIdx > 0 && i < separatorIdx;
            if (isHeader) {
                // Wrap in ** so EntitiesHelper renders it bold.
                // Strip any ** already inside cell text to avoid nesting.
                sb.append("**").append(joined.replace("**", "")).append("**");
            } else {
                sb.append(joined);
            }
        }
    }

    /**
     * Splits a raw table row string into trimmed cell content strings.
     * Leading/trailing {@code |} are stripped before splitting.
     */
    @NonNull
    private static String[] parseCells(@NonNull String row) {
        // Strip outer pipes: "| a | b |" → " a | b "
        String inner = row;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|"))   inner = inner.substring(0, inner.length() - 1);

        String[] raw = inner.split("\\|", -1);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim();
        }
        return raw;
    }

    /** Joins cells with a narrow pipe separator for display. */
    @NonNull
    private static String joinCells(@NonNull String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append("  │  ");
            sb.append(cells[i]);
        }
        return sb.toString();
    }
}
