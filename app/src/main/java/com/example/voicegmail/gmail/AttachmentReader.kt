package com.example.voicegmail.gmail

import android.content.Context
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum characters spoken aloud for a single attachment (avoids TTS timeouts). */
private const val MAX_ATTACHMENT_CHARS = 2_000

/**
 * Downloads an email attachment and extracts its text content so it can be
 * read aloud by the voice engine.
 *
 * Supported formats:
 *   - `text/plain`        → decoded directly
 *   - `text/html`         → HTML tags stripped via [Html.fromHtml]
 *   - `application/pdf`   → text extracted via PdfBox-Android
 *   - everything else     → graceful "cannot read aloud" message
 *
 * PdfBox initialisation is lazy and performed at most once per process.
 */
@Singleton
class AttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gmailRepository: GmailRepository
) {
    private var pdfBoxInitialized = false

    /**
     * Returns the readable text of [attachment] from message [messageId],
     * or a descriptive error string if extraction fails or the type is
     * unsupported.
     */
    suspend fun readAttachment(messageId: String, attachment: Attachment): String {
        return try {
            when {
                attachment.mimeType.startsWith("text/plain", ignoreCase = true) ->
                    readPlainText(messageId, attachment)

                attachment.mimeType.startsWith("text/html", ignoreCase = true) ->
                    readHtml(messageId, attachment)

                attachment.mimeType.equals("application/pdf", ignoreCase = true) ->
                    readPdf(messageId, attachment)

                else ->
                    "The attachment '${attachment.filename}' is a ${attachment.mimeType} file " +
                        "and cannot be read aloud. You can open it in another app."
            }
        } catch (e: Exception) {
            "Could not read '${attachment.filename}': ${e.message ?: "unknown error"}."
        }
    }

    // ------------------------------------------------------------------
    // Format-specific extractors
    // ------------------------------------------------------------------

    private suspend fun readPlainText(messageId: String, attachment: Attachment): String {
        val bytes = gmailRepository.downloadAttachmentData(messageId, attachment.id)
        val text = String(bytes, Charsets.UTF_8).trim()
        return if (text.isBlank())
            "The attachment '${attachment.filename}' appears to be empty."
        else
            text.take(MAX_ATTACHMENT_CHARS)
    }

    private suspend fun readHtml(messageId: String, attachment: Attachment): String {
        val bytes = gmailRepository.downloadAttachmentData(messageId, attachment.id)
        val html = String(bytes, Charsets.UTF_8)
        val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        return if (plain.isBlank())
            "The attachment '${attachment.filename}' appears to be empty."
        else
            plain.take(MAX_ATTACHMENT_CHARS)
    }

    private suspend fun readPdf(messageId: String, attachment: Attachment): String {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context)
            pdfBoxInitialized = true
        }
        val bytes = gmailRepository.downloadAttachmentData(messageId, attachment.id)
        val document = PDDocument.load(bytes)
        return try {
            val stripper = PDFTextStripper()
            val text = stripper.getText(document).trim()
            if (text.isBlank())
                "The PDF '${attachment.filename}' contains no readable text. " +
                    "It may be a scanned image."
            else
                text.take(MAX_ATTACHMENT_CHARS)
        } finally {
            document.close()
        }
    }
}
