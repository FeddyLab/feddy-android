package app.feddy.sdk.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ModelsTest {
    @Test
    fun decodesConversationDetail() {
        val json = """
        {
          "id": "conv-1", "status": "open", "subject": "Widget will not open", "last_seq": 2, "meta": null,
          "parts": [
            { "seq": 1, "author_type": "contact", "body": "Hello", "created_at": "2026-08-19T07:01:02.123Z" },
            { "seq": 2, "author_type": "teammate", "body": "Hi!", "created_at": "2026-08-19T07:05:00Z",
              "author_name": "Yu", "author_avatar_url": "https://example.com/yu.png", "bot_feedback": null },
            { "seq": 3, "author_type": "bot", "body": "Try updating.", "created_at": "2026-08-19T07:06:00Z",
              "author_name": "Helper", "author_avatar_url": null, "bot_feedback": "helpful" }
          ]
        }
        """
        val detail = ConversationDetail.parse(JSONObject(json))
        assertEquals(2, detail.lastSeq)
        assertEquals(3, detail.parts.size)
        assertTrue(detail.parts[2].isFromBot)
        assertEquals("helpful", detail.parts[2].botFeedback)
        assertNull(detail.parts[1].botFeedback)
        assertTrue(detail.parts[0].isFromContact)
        assertNull(detail.parts[0].authorName)
        assertFalse(detail.parts[1].isFromContact)
        assertEquals("Yu", detail.parts[1].authorName)
        assertEquals("https://example.com/yu.png", detail.parts[1].authorAvatarUrl)
        assertNull(detail.parts[2].authorAvatarUrl)
        assertNotEquals(detail.parts[0].authorRunKey, detail.parts[1].authorRunKey)
        assertEquals(IsoDates.parse("2026-08-19T07:01:02Z") + 123, detail.parts[0].createdAt)
    }

    @Test
    fun decodesConversationListWithUnread() {
        val json = """
        { "conversations": [ { "id": "conv-1", "subject": "Hello", "status": "pending", "last_seq": 3,
          "last_message_at": "2026-08-19T07:00:00.000Z", "seen_seq": 1 } ] }
        """
        val list = ConversationSummary.parseList(JSONObject(json))
        assertEquals(1, list.size)
        assertTrue(list[0].hasUnread)
        assertNull(list[0].lastMessage)
        assertNull(list[0].lastAuthorType)
    }

    @Test
    fun configEmailCaptureDefaultsToTrueWhenAbsent() {
        val gated = FeddyConfig.parse(JSONObject("""{ "brand": { "name": "Acme", "color": null, "logo_url": null }, "reply_sla_text": null, "email_capture": false, "categories": [] }"""))
        assertFalse(gated.emailCaptureEnabled)
        val legacy = FeddyConfig.parse(JSONObject("""{ "brand": { "name": "Acme", "color": null, "logo_url": null }, "reply_sla_text": null, "categories": [] }"""))
        assertTrue(legacy.emailCaptureEnabled)
        assertNull(legacy.brand.color)
        assertNull(legacy.brand.logoUrl)
        assertNull(legacy.replySlaText)
    }

    @Test
    fun configPlanSwitchesDefaultToShownAndOffered() {
        val business = FeddyConfig.parse(JSONObject("""{ "brand": { "name": "Acme" }, "categories": [{ "code": "bug", "label": "Bug" }], "branding": false, "attachments": false }"""))
        assertFalse(business.brandingEnabled)
        assertFalse(business.attachmentsEnabled)
        assertEquals(listOf(FeddyConfig.Category("bug", "Bug")), business.categories)
        val legacy = FeddyConfig.parse(JSONObject("""{ "brand": { "name": "Acme" } }"""))
        assertTrue(legacy.brandingEnabled)
        assertTrue(legacy.attachmentsEnabled)
        assertTrue(legacy.categories.isEmpty())
    }

    @Test
    fun parsesIsoDatesWithAndWithoutFractionsAndOffsets() {
        val base = IsoDates.parse("2026-08-19T07:00:00Z")
        assertEquals(1_787_122_800_000L, base)
        assertEquals(base + 500, IsoDates.parse("2026-08-19T07:00:00.5Z"))
        assertEquals(base + 123, IsoDates.parse("2026-08-19T07:00:00.123456Z"))
        assertEquals(base, IsoDates.parse("2026-08-19T09:00:00+02:00"))
        assertEquals(base, IsoDates.parse("2026-08-19T02:30:00-0430"))
        assertEquals(base, IsoDates.parse("2026-08-19 07:00:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDates() {
        IsoDates.parse("yesterday")
    }

    @Test
    fun keepsScalarAttributesAndConvertsDates() {
        val sanitized = ApiClient.sanitizeAttributes(
            mapOf(
                "plan" to "pro",
                "is_member" to true,
                "credits_left" to 42,
                "score" to 4.5,
                "ratio" to 0.5f,
                "trial_ends_at" to Date(1_755_600_000_000L),
                "nested" to mapOf("not" to "allowed"),
                "nothing" to null,
                "nan" to Double.NaN,
            ),
        )
        assertEquals("pro", sanitized["plan"])
        assertEquals(true, sanitized["is_member"])
        assertEquals(42L, sanitized["credits_left"])
        assertEquals(4.5, sanitized["score"])
        assertEquals(0.5, sanitized["ratio"])
        assertEquals("2025-08-19T10:40:00Z", sanitized["trial_ends_at"])
        assertFalse("nested" in sanitized)
        assertFalse("nothing" in sanitized)
        assertFalse("nan" in sanitized)
        // Must survive JSON encoding.
        JSONObject(sanitized).toString()
    }

    @Test
    fun emailValidationAcceptsAndRejects() {
        assertTrue(EmailValidation.isValid("user@example.com"))
        assertTrue(EmailValidation.isValid("a.b+c@sub.domain.io"))
        assertFalse(EmailValidation.isValid("nope"))
        assertFalse(EmailValidation.isValid("a b@example.com"))
        assertFalse(EmailValidation.isValid("user@nodot"))
    }

    @Test
    fun deviceModelSaysWhoMadeItOnce() {
        assertEquals("Samsung SM-S928B", DeviceContext.deviceModel("samsung", "SM-S928B"))
        assertEquals("Google Pixel 9", DeviceContext.deviceModel("Google", "Pixel 9"))
        assertEquals("OnePlus CPH2581", DeviceContext.deviceModel("OnePlus", "CPH2581"))
        // Already named: not said twice.
        assertEquals("Xiaomi 14", DeviceContext.deviceModel("Xiaomi", "Xiaomi 14"))
        assertEquals("motorola edge 50", DeviceContext.deviceModel("motorola", "motorola edge 50"))
        assertEquals("Pixel", DeviceContext.deviceModel("", "Pixel"))
        assertEquals("unknown", DeviceContext.deviceModel(null, null))
    }

    @Test
    fun brandColourParsingAndContrast() {
        assertEquals(Rgb.parse("#fff"), Rgb.parse("FFFFFF"))
        assertEquals(0.0, Rgb.parse(" #000000 ")!!.luminance, 0.0)
        assertNull(Rgb.parse("12345"))
        assertNull(Rgb.parse("gggggg"))
        assertNull(Rgb.parse(null))
        assertTrue(Rgb.parse("#FFEB3B")!!.needsDarkText)
        assertTrue(Rgb.parse("#FFFFFF")!!.needsDarkText)
        assertFalse(Rgb.parse("#6366F1")!!.needsDarkText)
        assertFalse(Rgb.parse("#000000")!!.needsDarkText)
        assertEquals(0xFF4F5AFA.toInt(), Rgb.parse("#4F5AFA")!!.argb)
    }

    @Test
    fun attachmentsLeaveAsJpegNamedSo() {
        assertEquals("shot.jpg", AttachmentUpload.jpegName("shot.png"))
        assertEquals("image.jpg", AttachmentUpload.jpegName(".png"))
        assertEquals("image.jpg", AttachmentUpload.jpegName(""))
        assertEquals("archive.tar.jpg", AttachmentUpload.jpegName("archive.tar.gz"))
        assertEquals("plain.jpg", AttachmentUpload.jpegName("plain"))
    }

    @Test
    fun retryableErrors() {
        assertTrue(java.io.IOException("offline").isRetryable())
        assertTrue(ApiError(429, "").isRetryable())
        assertTrue(ApiError(503, "").isRetryable())
        assertFalse(ApiError(400, "").isRetryable())
        assertFalse(ApiError(0, "invalid URL").isRetryable())
    }
}
