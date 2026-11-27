package io.github.binoverfl0w.zencampus.data

import io.ktor.http.Url
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * One browser window, one Microsoft origin. Per-tab sessionStorage carries the submission state across
 * reloads on that origin. Pinning the origin also prevents a second submission after a cross-origin redirect.
 */
internal class MicrosoftSignInAttempt(
    private val credentials: SavedSignIn,
    private val rejectionMessage: String,
    private val originForPage: (String) -> String? = ::microsoftSignInOrigin,
    private val fixture: Boolean = false,
) : SignInAttempt {
    private val token = Random.nextLong().toULong().toString(16)
    private var origin: String? = null

    /** Call only for a completed main-frame load. The browser never reads a result from this script. */
    override fun scriptForPage(url: String): String? {
        val pageOrigin = originForPage(url) ?: return null
        if (!fixture && pageOrigin != credentials.site) return null
        if (origin == null) origin = pageOrigin
        if (origin != pageOrigin) return null
        return microsoftSignInScript(pageOrigin, token, credentials.username, credentials.password, rejectionMessage)
    }
}

internal fun scriptString(value: String): String = JsonPrimitive(value).toString()
    .replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal fun microsoftSignInScript(origin: String, token: String, email: String, password: String, rejection: String): String = """
    (() => {
        const origin = ${scriptString(origin)};
        const host = ${scriptString(Url(origin).host)};
        if (window !== window.top || location.hostname !== host || location.origin !== origin) return;

        const key = 'zencampus.signin';
        const attempt = ${scriptString(token)};
        const controllerKey = key + '.controller';
        if (window[controllerKey]?.token === attempt) return;
        window[controllerKey]?.stop();
        if (sessionStorage.getItem(key) !== attempt + ':rejected') {
            document.getElementById('zencampus-saved-sign-in-error')?.remove();
        }

        const email = ${scriptString(email)};
        let password = ${scriptString(password)};
        let submittedHere = false;
        let leftPasswordStep = false;
        let advancedEmail = false;

        const visible = field => field && field.getClientRects().length > 0 &&
            getComputedStyle(field).visibility === 'visible' && !field.closest('[aria-hidden="true"], [inert]');
        const set = (field, value) => {
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(field, value);
            field.dispatchEvent(new Event('input', { bubbles: true }));
            field.dispatchEvent(new Event('change', { bubbles: true }));
        };
        const reject = () => {
            password = '';
            sessionStorage.setItem(key, attempt + ':rejected');
            if (document.getElementById('zencampus-saved-sign-in-error')) return;
            const notice = document.createElement('p');
            notice.id = 'zencampus-saved-sign-in-error';
            notice.setAttribute('role', 'alert');
            notice.style.cssText = 'margin:0;padding:12px;background:#fff4ce;color:#241a09;';
            notice.textContent = ${scriptString(rejection)};
            document.body.prepend(notice);
        };

        const update = () => {
            // Storage can be blocked. In that case the enclosing guard stops automatic filling.
            const state = sessionStorage.getItem(key);
            const field = document.querySelector('input[type=password][name=passwd]');
            const shown = visible(field);
            const error = document.querySelector('#passwordError');
            const passwordError = visible(error) && error.textContent.trim();
            if (state === attempt + ':rejected') {
                if (shown || passwordError) reject();
                return;
            }
            if (state === attempt + ':submitted') {
                password = '';
                if (submittedHere && !shown) leftPasswordStep = true;
                if (passwordError || (shown && (!submittedHere || leftPasswordStep))) reject();
                return;
            }

            const next = document.getElementById('idSIButton9');
            if (shown) {
                if (!visible(next) || next.disabled || field.disabled || field.readOnly || field.value) return;
                const identity = document.querySelector('#displayName, #userDisplayName');
                const account = visible(identity) ? (identity.getAttribute('title') || identity.textContent).trim() : '';
                if (account.includes('@') && account.toLowerCase() !== email.toLowerCase()) return;
                const form = field.form;
                const sameOriginPost = () => {
                    if (!form || field.form !== form || next.form !== form ||
                        (next.getAttribute('formmethod') || form.method).toLowerCase() !== 'post') return false;
                    const action = new URL(next.getAttribute('formaction') || form.action, location.href);
                    return action.origin === origin && !action.username && !action.password;
                };
                if (!sameOriginPost()) return;
                sessionStorage.setItem(key, attempt + ':submitted');
                submittedHere = true;
                set(field, password);
                password = '';
                if (sameOriginPost()) next.click();
                return;
            }

            // The password step may replace the email step without a navigation.
            if (advancedEmail || state === attempt + ':email') return;
            const login = document.querySelector('input[name=loginfmt]');
            if (visible(login) && (login.type === 'email' || login.type === 'text') &&
                login.autocomplete !== 'one-time-code' && !login.disabled && !login.readOnly && visible(next) && !next.disabled) {
                advancedEmail = true;
                sessionStorage.setItem(key, attempt + ':email');
                set(login, email);
                next.click();
                return;
            }
            const tiles = document.querySelectorAll(
                '#tilesHolder [role="button"], [data-test-id="accountTile"], [data-test-id="tile"], .tile[role="button"]');
            for (const tile of tiles) {
                if (!visible(tile)) continue;
                const labels = [tile.getAttribute('data-username'), tile.getAttribute('data-test-id'),
                    ...(tile.innerText || '').split(/\r?\n/)];
                if (labels.some(label => label && label.trim().toLowerCase() === email.toLowerCase())) {
                    advancedEmail = true;
                    sessionStorage.setItem(key, attempt + ':email');
                    tile.click();
                    return;
                }
            }
        };

        const observer = new MutationObserver(run);
        window[controllerKey] = {
            token: attempt,
            stop: () => { password = ''; observer.disconnect(); }
        };
        function run() {
            try { update(); } catch (_) { password = ''; observer.disconnect(); }
        }
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
        run();
    })();
""".trimIndent()
