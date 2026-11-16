package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MonoTextStyle

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<Pair<String, String>>) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Composable
internal fun CompareRichMarkdownText(
    text: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val colorScheme = MaterialTheme.colorScheme
    val inlineCodeBg = colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val linkColor = colorScheme.primary

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEachIndexed { idx, block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            3 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.labelLarge
                        }
                        Text(
                            text = formatInlineMarkdown(block.text, linkColor, inlineCodeBg),
                            style = style,
                            color = colorScheme.onSurface
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = formatInlineMarkdown(block.text, linkColor, inlineCodeBg),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onSurface
                        )
                    }

                    is MarkdownBlock.BlockQuote -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(colorScheme.primary)
                                )
                                Text(
                                    text = formatInlineMarkdown(block.text, linkColor, inlineCodeBg),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    is MarkdownBlock.ListBlock -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            block.items.forEach { (marker, itemText) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = marker,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.primary
                                    )
                                    Text(
                                        text = formatInlineMarkdown(itemText, linkColor, inlineCodeBg),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        val highlighted = remember(block.code, block.language, colorScheme) {
                            highlightCodeSnippet(
                                code = block.code,
                                baseColor = colorScheme.onSurface,
                                keywordColor = colorScheme.primary,
                                stringColor = colorScheme.tertiary,
                                commentColor = colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compare_code_block_$idx")
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (block.language.isNotBlank()) {
                                    Text(
                                        text = block.language.lowercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = highlighted,
                                        style = MonoTextStyle,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.TableBlock -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    block.headers.forEach { h ->
                                        Text(
                                            text = formatInlineMarkdown(h, linkColor, inlineCodeBg),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(min = 72.dp, max = 180.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                                block.rows.forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        block.headers.indices.forEach { colIdx ->
                                            val cell = row.getOrElse(colIdx) { "" }
                                            Text(
                                                text = formatInlineMarkdown(cell, linkColor, inlineCodeBg),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.widthIn(min = 72.dp, max = 180.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MarkdownBlock.Divider -> {
                        HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

private val ORDERED_ITEM_RE = Regex("^(\\d+\\.)\\s+(.*)$")
private val HEADING_RE = Regex("^(#{1,4})\\s+(.*)$")
private val TABLE_SEP_RE = Regex("^\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?$")

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isEmpty()) return emptyList()
    val lines = raw.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }
        // Fenced code block (supports unclosed fence while streaming)
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size && lines[i].trim().startsWith("```")) i++
            blocks += MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n"))
            continue
        }
        // Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks += MarkdownBlock.Divider
            i++
            continue
        }
        // Heading
        val headingMatch = HEADING_RE.matchEntire(trimmed)
        if (headingMatch != null) {
            blocks += MarkdownBlock.Heading(
                level = headingMatch.groupValues[1].length,
                text = headingMatch.groupValues[2].trim()
            )
            i++
            continue
        }
        // Table
        if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP_RE.matches(lines[i + 1].trim())) {
            val headers = splitPipeRow(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                rows += splitPipeRow(lines[i].trim())
                i++
            }
            blocks += MarkdownBlock.TableBlock(headers, rows)
            continue
        }
        // BlockQuote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines += lines[i].trim().removePrefix(">").trimStart()
                i++
            }
            blocks += MarkdownBlock.BlockQuote(quoteLines.joinToString("\n"))
            continue
        }
        // Unordered list
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) {
                    items += "•" to t.drop(2).trimStart()
                    i++
                } else break
            }
            blocks += MarkdownBlock.ListBlock(ordered = false, items = items)
            continue
        }
        // Ordered list
        val firstOrdered = ORDERED_ITEM_RE.matchEntire(trimmed)
        if (firstOrdered != null) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val m = ORDERED_ITEM_RE.matchEntire(lines[i].trim()) ?: break
                items += m.groupValues[1] to m.groupValues[2]
                i++
            }
            blocks += MarkdownBlock.ListBlock(ordered = true, items = items)
            continue
        }
        // Paragraph (until blank line or next structural token)
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() ||
                t.startsWith("```") ||
                t.startsWith(">") ||
                HEADING_RE.matches(t) ||
                t.startsWith("- ") ||
                t.startsWith("* ") ||
                ORDERED_ITEM_RE.matches(t)
            ) {
                break
            }
            paraLines += lines[i]
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paraLines.joinToString("\n"))
        }
    }
    return blocks
}

private fun splitPipeRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private val INLINE_TOKEN_RE = Regex(
    "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)|(\\[([^\\]]+)]\\((https?://[^)\\s]+)\\))"
)

private fun formatInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBgColor: Color
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE_TOKEN_RE.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val codeToken = match.groups[1]?.value
        val boldToken = match.groups[2]?.value
        val italicToken = match.groups[3]?.value
        val linkLabel = match.groups[5]?.value
        val linkUrl = match.groups[6]?.value
        when {
            codeToken != null -> {
                withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        background = codeBgColor,
                        fontWeight = FontWeight.Medium
                    )
                ) {
                    append(codeToken.removeSurrounding("`"))
                }
            }
            boldToken != null -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldToken.removeSurrounding("**"))
                }
            }
            italicToken != null -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italicToken.removeSurrounding("*"))
                }
            }
            linkLabel != null && linkUrl != null -> {
                withLink(
                    LinkAnnotation.Url(
                        url = linkUrl,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    )
                ) {
                    append(linkLabel)
                }
            }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when",
    "for", "while", "import", "package", "private", "public", "internal", "suspend",
    "def", "async", "await", "const", "let", "function", "export", "from", "try", "catch",
    "true", "false", "null", "None", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE"
)

private val CODE_TOKEN_RE = Regex(
    "(//[^\\n]*|#[^\\n]*)|(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')|\\b([A-Za-z_][A-Za-z0-9_]*)\\b"
)

private fun highlightCodeSnippet(
    code: String,
    baseColor: Color,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = baseColor)) {
        var cursor = 0
        for (m in CODE_TOKEN_RE.findAll(code)) {
            if (m.range.first > cursor) append(code.substring(cursor, m.range.first))
            val comment = m.groups[1]?.value
            val strLit = m.groups[2]?.value
            val ident = m.groups[5]?.value
            when {
                comment != null -> withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(comment) }
                strLit != null -> withStyle(SpanStyle(color = stringColor)) { append(strLit) }
                ident != null && ident in CODE_KEYWORDS -> withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)) { append(ident) }
                else -> append(m.value)
            }
            cursor = m.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}
