package io.github.binoverfl0w.zencampus.model

import kotlin.test.*

/** The signed-in in-app browser: any DigiCampus page the student opens, never sign-out, never straight into an exercise. */
class CampusLinksTest {
    private val forum = "$DIGICAMPUS_ORIGIN/dispatch.php/course/forum?cid=c1"
    private fun go(url: String, course: String? = null, page: String = forum, gesture: Boolean = true, preview: Boolean = false) =
        CampusLinks.navigation(url, course, page, gesture, preview)

    @Test fun anyDigiCampusPageCanBeOpenedWhereItIs() {
        assertEquals(forum, CampusLinks.entry(forum))
        assertEquals("$DIGICAMPUS_ORIGIN/dispatch.php/messages#top", CampusLinks.entry("$DIGICAMPUS_ORIGIN/dispatch.php/messages#top"))
        assertEquals("c1", CampusLinks.courseOf(forum))
        assertNull(CampusLinks.courseOf("$DIGICAMPUS_ORIGIN/dispatch.php/course/forum?cid=c1&cid=c2"))
    }

    @Test fun aFileOpensAtItsFolderPageNotAtItsDownload() {
        // DigiCampus gives files a relative download address, which is neither a page nor something the browser opens.
        val file = Document("f1", "c1", "d1", "Sheet.pdf", externalUrl = "/sendfile.php?type=0&file_id=f1")
        assertEquals("$DIGICAMPUS_ORIGIN/dispatch.php/course/files/index/d1?cid=c1", CampusLinks.entry(documentPageUrl(file)))
        assertEquals(vipsSheetsUrl("c1"), CampusLinks.entry(documentPageUrl(file.copy(folderId = VIPS_ATTACHMENTS))))
        assertEquals(courseFilesUrl("c1"), CampusLinks.entry(documentPageUrl(file.copy(folderId = ""))))
    }

    @Test fun otherSitesSignInAndSignOutAreNotOpenedInIt() {
        assertNull(CampusLinks.entry("https://www.uni-augsburg.de/de/"))
        assertNull(CampusLinks.entry("http://digicampus.uni-augsburg.de/dispatch.php/start"))
        assertNull(CampusLinks.entry("https://digicampus.uni-augsburg.de.example.org/dispatch.php/start"))
        assertNull(CampusLinks.entry("https://user:pass@digicampus.uni-augsburg.de/dispatch.php/start"))
        assertNull(CampusLinks.entry("$DIGICAMPUS_ORIGIN/logout.php"))
        assertNull(CampusLinks.entry("$DIGICAMPUS_ORIGIN/index.php?again=yes"))
    }

    @Test fun vipsOpensOnlyAtListsNeverAtAnExercise() {
        assertEquals(vipsSheetsUrl("c1"), CampusLinks.entry(vipsSheetsUrl("c1")))
        assertEquals(vipsResultsUrl("c1"), CampusLinks.entry(vipsResultsUrl("c1"), "c1"))
        assertNull(CampusLinks.entry("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_exercise?assignment_id=3&exercise_id=1&cid=c1"))
        assertNull(CampusLinks.entry("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets"), "a Vips address needs its course")
    }

    @Test fun insideTheBrowserEveryDigiCampusPageIsFollowed() {
        assertEquals(CampusRoute.PAGE, go("$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions/student_assignment_solutions?assignment_id=4&cid=c1", "c1"))
        assertEquals(CampusRoute.PAGE, go("$DIGICAMPUS_ORIGIN/dispatch.php/course/overview?cid=c1", "c1", gesture = false), "redirects stay inside")
        assertEquals(CampusRoute.PAGE, go("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_exercise?assignment_id=3&exercise_id=1&cid=c1"))
        assertEquals(CampusRoute.BLOCK, go("$DIGICAMPUS_ORIGIN/logout.php"))
        assertEquals(CampusRoute.RECONNECT, go("$DIGICAMPUS_ORIGIN/index.php", gesture = false))
        assertEquals(CampusRoute.RECONNECT, go("https://login.uni-augsburg.de/idp/profile/SAML2/Redirect/SSO", gesture = false))
        assertEquals(CampusRoute.EXTERNAL, go("https://example.org/paper.html"))
        assertEquals(CampusRoute.BLOCK, go("https://example.org/paper.html", gesture = false))
        assertEquals(CampusRoute.BLOCK, go("javascript:alert(1)"))
    }

    @Test fun filesGoToTheAppsDownloadWithTheirCourse() {
        val own = "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&file_id=f1&file_name=Notes.pdf&cid=c2"
        assertEquals(CampusRoute.ATTACHMENT, go(own, "c1"))
        assertEquals("c2", CampusLinks.attachmentCourse(own, "c1", forum), "the file's own course comes first")
        val bare = "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&file_id=f1&file_name=Notes.pdf"
        assertEquals("c1", CampusLinks.attachmentCourse(bare, null, forum), "else the page's course")
        assertNull(CampusLinks.attachmentCourse(bare, null, "$DIGICAMPUS_ORIGIN/dispatch.php/start"))
        assertEquals(CampusRoute.PAGE, go(bare, page = "$DIGICAMPUS_ORIGIN/dispatch.php/start"), "the page reports it as unsupported")
    }

    @Test fun thePreviewNeverLeavesTheVipsSample() {
        assertEquals(CampusRoute.PAGE, go(vipsSheetsUrl("c1"), "c1", preview = true))
        assertEquals(CampusRoute.BLOCK, go(forum, "c1", preview = true))
        assertEquals(CampusRoute.BLOCK, go("https://example.org/", "c1", preview = true))
    }

    @Test fun filesFromOtherPagesGoInTheCourseFolder() {
        assertEquals(emptyList(), vipsAttachmentFolders(forum, emptyList()))
        assertEquals(listOf("Vips"), vipsAttachmentFolders(vipsResultsUrl("c1"), emptyList()))
    }
}
