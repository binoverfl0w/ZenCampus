package io.github.binoverfl0w.zencampus.data

import io.ktor.http.Url
import kotlin.random.Random

internal class LibrarySignInAttempt(
    private val entry: SavedSignIn,
    private val rejection: String,
    private val originForPage: (String) -> String? = ::librarySignInOrigin,
    private val fixture: Boolean = false,
) : SignInAttempt {
    private val token = Random.nextLong().toULong().toString(16)
    private var origin: String? = null

    override fun scriptForPage(url: String): String? {
        val site = originForPage(url) ?: return null
        if (!fixture && site != entry.site) return null
        if (origin == null) origin = site
        if (origin != site) return null
        return librarySignInScript(site, token, entry.username, entry.password, rejection)
    }
}

internal fun librarySignInScript(origin: String, token: String, username: String, password: String, rejection: String): String = """
    (() => {
        const host = ${scriptString(Url(origin).host)};
        const origin = ${scriptString(origin)};
        if (window !== window.top || location.hostname !== host || location.origin !== origin) return;

        const key = 'zencampus.signin';
        const attempt = ${scriptString(token)};
        const controllerKey = key + '.controller';
        if (window[controllerKey]?.token === attempt) return;
        window[controllerKey]?.stop();
        if (sessionStorage.getItem(key) !== attempt + ':rejected') {
            document.getElementById('zencampus-saved-sign-in-error')?.remove();
        }

        let password = ${scriptString(password)};
        const username = ${scriptString(username)};
        const visible = field => field && field.getClientRects().length > 0 &&
            getComputedStyle(field).visibility === 'visible' && !field.closest('[aria-hidden="true"], [inert]');
        const usable = field => visible(field) && !field.disabled && !field.readOnly;
        const set = (field, value) => {
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(field, value);
            field.dispatchEvent(new Event('input', { bubbles: true }));
            field.dispatchEvent(new Event('change', { bubbles: true }));
        };
        const allowedAction = (form, submit) => {
            const action = new URL(submit.getAttribute('formaction') || form.action, location.href);
            return (submit.getAttribute('formmethod') || form.method).toLowerCase() === 'post' &&
                action.origin === origin && !action.username && !action.password &&
                action.pathname.split(';')[0] === '/TouchPoint/login.do' &&
                (!action.searchParams.has('methodToCall') || action.searchParams.get('methodToCall') === 'login');
        };
        const reject = () => {
            sessionStorage.setItem(key, attempt + ':rejected');
            if (document.getElementById('zencampus-saved-sign-in-error')) return;
            const notice = document.createElement('p');
            notice.id = 'zencampus-saved-sign-in-error';
            notice.setAttribute('role', 'alert');
            notice.style.cssText = 'margin:0;padding:12px;background:#fff4ce;color:#241a09;';
            notice.textContent = ${scriptString(rejection)};
            document.body.prepend(notice);
        };
        let submittedForm = null;
        const update = () => {
            const state = sessionStorage.getItem(key);
            const form = document.querySelector('form#LoginBean');
            if (!form || !visible(form)) return;
            const error = form.querySelector('[role=alert], .error, .errorMessage');
            if (state === attempt + ':rejected' || (state === attempt + ':submitted' &&
                (submittedForm !== form || (visible(error) && error.textContent.trim())))) {
                password = '';
                reject();
                return;
            }
            if (state === attempt + ':submitted') return;

            // The TouchPoint form is identified by LoginBean and its login action. Fail closed on ambiguous inputs.
            const fields = Array.from(form.elements).filter(field => field instanceof HTMLInputElement && usable(field));
            const secrets = fields.filter(field => field.type === 'password' &&
                (field.autocomplete === '' || field.autocomplete === 'current-password' || field.autocomplete === 'off'));
            const users = fields.filter(field => field.type === 'text' && field.name !== '' &&
                (field.autocomplete === '' || field.autocomplete === 'username' || field.autocomplete === 'off') &&
                !/otp|code|token|factor|authenticator|pin/i.test(field.name + ' ' + field.id));
            const submits = Array.from(form.elements).filter(field =>
                field.type === 'submit' && usable(field) && allowedAction(form, field));
            if (users.length !== 1 || secrets.length !== 1 || submits.length !== 1) return;
            const [submit] = submits;
            const [user] = users;
            const [secret] = secrets;
            if (secret.value) return;
            document.getElementById('zencampus-saved-sign-in-error')?.remove();
            sessionStorage.setItem(key, attempt + ':submitted');
            submittedForm = form;
            set(user, username);
            if (user.form !== form || secret.form !== form || submit.form !== form || !allowedAction(form, submit)) {
                password = '';
                reject();
                return;
            }
            set(secret, password);
            password = '';
            if (secret.form === form && submit.form === form && allowedAction(form, submit)) submit.click();
        };
        const observer = new MutationObserver(run);
        window[controllerKey] = { token: attempt, stop: () => { password = ''; observer.disconnect(); } };
        function run() {
            try { update(); } catch (_) { password = ''; observer.disconnect(); }
        }
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
        run();
    })();
""".trimIndent()
