package io.github.binoverfl0w.zencampus

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import io.github.binoverfl0w.zencampus.data.SignInKind
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.services.savedSignInUsername
import io.github.binoverfl0w.zencampus.ui.service
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject

@Composable
internal fun IosSignInFields(kind: SignInKind, username: String, onUsername: (String) -> Unit, password: String, onPassword: (String) -> Unit,
                            visible: Boolean, onToggle: () -> Unit) {
    val words = LocalWords.current
    CredentialField(words.savedSignInUsername(kind), username, onUsername, password = false, secure = false, email = kind == SignInKind.STUDY)
    CredentialField(words.service("saved_password"), password, onPassword, password = true, secure = !visible)
    ZenLink(words.service(if (visible) "saved_hide_password" else "saved_show_password"), onToggle)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun CredentialField(label: String, value: String, onValue: (String) -> Unit, password: Boolean, secure: Boolean, email: Boolean = false) {
    val updated = rememberUpdatedState(onValue)
    val target = remember { SignInFieldTarget { updated.value(it) } }
    UIKitView(
        factory = {
            UITextField().apply {
                borderStyle = UITextBorderStyle.UITextBorderStyleRoundedRect
                keyboardType = if (email) UIKeyboardTypeEmailAddress else UIKeyboardTypeDefault
                autocapitalizationType = UITextAutocapitalizationType.UITextAutocapitalizationTypeNone
                autocorrectionType = UITextAutocorrectionType.UITextAutocorrectionTypeNo
                spellCheckingType = UITextSpellCheckingType.UITextSpellCheckingTypeNo
                textContentType = if (password) UITextContentTypePassword else UITextContentTypeUsername
                addTarget(target, NSSelectorFromString("edited:"), UIControlEventEditingChanged)
            }
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        update = { field ->
            field.placeholder = label
            field.accessibilityLabel = label
            if (field.secureTextEntry != secure) {
                field.secureTextEntry = secure
                field.text = value
            } else if (field.text != value) field.text = value
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
private class SignInFieldTarget(val change: (String) -> Unit) : NSObject() {
    @ObjCAction fun edited(field: UITextField) { change(field.text.orEmpty()) }
}
