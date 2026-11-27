package io.github.binoverfl0w.zencampus.data

import kotlin.test.*

class SignInOriginsTest {
    @Test fun keepsTheOfficialUniversityAndMicrosoftSsoFlowsInTheApp() {
        assertTrue(isTrustedSignInUrl("https://digicampus.uni-augsburg.de/dispatch.php/login"))
        assertTrue(isTrustedSignInUrl("https://idp.rz.uni-augsburg.de/simplesaml/"))
        assertTrue(isTrustedSignInUrl("https://login.microsoftonline.com/tenant/oauth2/authorize"))
    }
    @Test fun rejectsLookalikeDomainsCredentialsAndInsecureSignIn() {
        listOf(
            "http://idp.rz.uni-augsburg.de/",
            "https://login.microsoftonline.com.evil.example/",
            "https://fakeuni-augsburg.de/",
            "https://user:secret@login.microsoftonline.com/",
            "https://login.microsoftonline.com:8443/",
            "javascript:alert(1)",
        ).forEach { assertFalse(isTrustedSignInUrl(it), it) }
    }
}
