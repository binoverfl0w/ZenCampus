package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.boardTint
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

// The signed-out start: the welcome screen and the app brand.

@Composable
internal fun WelcomeScreen(onSignIn: () -> Unit, onCampus: () -> Unit, onPreview: (() -> Unit)? = null, onSavedSignIns: (() -> Unit)? = null) {
    val words = LocalWords.current
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
            Brand()
            Spacer(Modifier.height(56.dp))
            Text(words.service("university_name"), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(words.text("welcome_body"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampusBoard.entries.forEach { board ->
                    val tint = boardTint(board)
                    Column(Modifier.weight(1f).background(tint.soft, RoundedCornerShape(Zen.radiusLarge)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        ZenIcon(boardSymbol(board), Modifier.size(22.dp), tint.ink)
                        Text(words.board(board), style = MaterialTheme.typography.titleSmall, color = tint.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            ZenButton(words.text("sign_in"), onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), style = ButtonStyle.PRIMARY, trailing = Symbol.NEXT)
            Spacer(Modifier.height(10.dp))
            ZenButton(words.service("public_campus"), onCampus, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), style = ButtonStyle.OUTLINE)
            if (onPreview != null) {
                Spacer(Modifier.height(10.dp))
                ZenButton(words.text("preview_mode"), onPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), style = ButtonStyle.OUTLINE)
            }
            if (onSavedSignIns != null) ZenLink(words.service("saved_sign_ins"), onSavedSignIns)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ZenIcon(Symbol.LEAF, Modifier.size(19.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("ZenCampus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
