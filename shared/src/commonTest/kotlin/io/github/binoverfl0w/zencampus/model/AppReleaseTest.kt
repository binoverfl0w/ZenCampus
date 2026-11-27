package io.github.binoverfl0w.zencampus.model

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppReleaseTest {
    @Test fun versionsCompareNumericallyAndPreReleasesComeFirst() {
        fun v(text: String) = assertNotNull(AppVersion.parse(text), text)
        assertTrue(v("0.12.0") > v("0.11.9"))
        assertTrue(v("v0.10.0") > v("0.9.12"))
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
        assertTrue(v("0.12.0-beta.1") < v("0.12.0"))
        assertTrue(v("0.12.0-beta.2") > v("0.12.0-beta.1"))
        assertEquals("0.12.0", v("v0.12.0+build.7").toString())
        assertNull(AppVersion.parse("Development"))
        assertNull(AppVersion.parse("1.x"))
    }

    @Test fun latestReleaseKeepsAssetsDigestsAndNotes() {
        val release = assertNotNull(parseGitHubRelease("""{
            "tag_name": "v0.12.0", "name": "ZenCampus 0.12.0", "draft": false, "prerelease": false,
            "html_url": "https://github.com/example/zencampus/releases/tag/v0.12.0",
            "body": "Updates\r\n- in the app",
            "assets": [
              {"name": "zencampus-0.12.0.apk", "size": 31457280, "digest": "sha256:${"AB".repeat(32)}",
               "browser_download_url": "https://github.com/example/zencampus/releases/download/v0.12.0/zencampus-0.12.0.apk"},
              {"name": "SHA256SUMS", "size": 300, "digest": null,
               "browser_download_url": "https://github.com/example/zencampus/releases/download/v0.12.0/SHA256SUMS"},
              {"name": "insecure.apk", "size": 1, "browser_download_url": "http://example.org/insecure.apk"}
            ]}"""))
        assertEquals("0.12.0", release.version)
        assertEquals("ZenCampus 0.12.0", release.title)
        assertEquals("Updates\n- in the app", release.notes)
        assertEquals(listOf("zencampus-0.12.0.apk", "SHA256SUMS"), release.assets.map { it.name })
        assertEquals("ab".repeat(32), release.assets[0].sha256)
        assertNull(release.assets[1].sha256)
        assertEquals(31457280, release.assets[0].size)
    }

    @Test fun draftsPreReleasesAndForeignPagesAreNeverOffered() {
        val page = "\"html_url\": \"https://github.com/example/zencampus/releases/tag/v1.0.0\""
        assertNull(parseGitHubRelease("""{"tag_name": "v1.0.0", $page, "draft": true}"""))
        assertNull(parseGitHubRelease("""{"tag_name": "v1.0.0", $page, "prerelease": true}"""))
        assertNull(parseGitHubRelease("""{"tag_name": "v1.0.0", "html_url": "https://example.org/release"}"""))
        assertNull(parseGitHubRelease("""{"tag_name": "nightly", $page}"""))
        assertNull(parseGitHubRelease("not json"))
    }

    @Test fun checksumFilesAndDigestsAreRead() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(mapOf("zencampus-0.12.0.apk" to hex, "zencampus-0.12.0-linux-x86_64.tar.gz" to hex),
            parseSha256Sums("$hex  zencampus-0.12.0.apk\n${hex.uppercase()} *zencampus-0.12.0-linux-x86_64.tar.gz\n\nnot a line"))
        assertEquals(hex, sha256Digest("sha256:$hex"))
        assertNull(sha256Digest("sha512:$hex"))
        assertNull(sha256Digest("sha256:short"))
    }

    @Test fun scrubbingRemovesCredentialsContactsQueriesAndIdentifiers() {
        val scrubbed = LogScrubber.scrub(
            "GET https://digicampus.uni-augsburg.de/sendfile.php?file_id=abc&file_name=Notes.pdf failed; " +
            "Cookie: Seminar_Session=s3cr3t; token=abc123 Authorization: Bearer eyJhbGciOi.payload " +
            "mail student@example.org course 0123456789abcdef0123456789abcdef in /home/someone/Downloads " +
            "matriculation 1234567 status 404")
        listOf("s3cr3t", "abc123", "eyJhbGciOi", "student@example.org", "Notes.pdf", "0123456789abcdef0123456789abcdef", "someone", "1234567")
            .forEach { assertFalse(it in scrubbed, "$it survived: $scrubbed") }
        assertTrue("https://digicampus.uni-augsburg.de/sendfile.php?<query>" in scrubbed, scrubbed)
        assertTrue("status 404" in scrubbed)
        assertTrue("/home/~/Downloads" in scrubbed, scrubbed)
    }

    @Test fun dsnBecomesTheEnvelopeEndpoint() {
        val dsn = assertNotNull(CrashDsn.parse("https://publickey@o123.ingest.example.io/4567"))
        assertEquals("https://o123.ingest.example.io/api/4567/envelope/", dsn.endpoint)
        assertEquals("publickey", dsn.publicKey)
        assertEquals("https://glitch.example.org:8443/errors/api/9/envelope/",
            CrashDsn.parse("https://key@glitch.example.org:8443/errors/9")?.endpoint)
        assertNull(CrashDsn.parse(""))
        assertNull(CrashDsn.parse("http://key@insecure.example.org/1"))
        assertNull(CrashDsn.parse("https://example.org/1"))
    }

    @Test fun envelopeOrdersCausesAndFramesAsSentryExpectsAndCarriesNoUser() {
        val report = CrashReport("f".repeat(32), 1_700_000_000_000, "0.12.0", "android", "Android", "15 (API 35)", "Pixel 8", "main",
            exceptions = listOf(
                CrashException("java.lang.IllegalStateException", "outer", listOf(CrashFrame("inner", "io.github.binoverfl0w.zencampus.ui.A", "A.kt", 3), CrashFrame("outer", "android.os.Looper"))),
                CrashException("java.io.IOException", "cause", listOf(CrashFrame("read", "io.github.binoverfl0w.zencampus.data.B", "B.kt", 9))),
            ),
            log = listOf(LogLine(1_699_999_999_000, LogLevel.WARN, "refresh", "courses: OFFLINE")),
            mappingId = "4f099406-825c-5e38-adba-5021d7466f78")
        val lines = sentryEnvelope(report, CrashDsn.parse("https://key@sentry.example.org/1")!!).trimEnd().lines()
        assertEquals(3, lines.size)
        assertEquals("f".repeat(32), Json.parseToJsonElement(lines[0]).jsonObject["event_id"]?.jsonPrimitive?.content)
        val event = Json.parseToJsonElement(lines[2]).jsonObject
        val values = event["exception"]!!.jsonObject["values"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("IOException", "IllegalStateException"), values.map { it["type"]!!.jsonPrimitive.content })
        val frames = values[1]["stacktrace"]!!.jsonObject["frames"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonPrimitive.content }
        assertEquals(listOf("outer", "inner"), frames)
        assertEquals("zencampus@0.12.0", event["release"]!!.jsonPrimitive.content)
        assertNull(event["user"]); assertNull(event["server_name"])
        val image = event["debug_meta"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject
        assertEquals("proguard", image["type"]!!.jsonPrimitive.content)
        assertEquals("4f099406-825c-5e38-adba-5021d7466f78", image["uuid"]!!.jsonPrimitive.content)
        assertNull(Json.parseToJsonElement(sentryEnvelope(report.copy(mappingId = ""), CrashDsn.parse("https://key@sentry.example.org/1")!!)
            .trimEnd().lines()[2]).jsonObject["debug_meta"], "unminified builds name no mapping")
        assertEquals("warning", event["breadcrumbs"]!!.jsonObject["values"]!!.jsonArray[0].jsonObject["level"]!!.jsonPrimitive.content)
    }
}
