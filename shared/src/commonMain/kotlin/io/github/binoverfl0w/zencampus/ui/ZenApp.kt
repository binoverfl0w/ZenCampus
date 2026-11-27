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
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.CampusContentFrame
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.LocalAppVersion
import io.github.binoverfl0w.zencampus.ui.design.LocalCourseHues
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalReducedMotion
import io.github.binoverfl0w.zencampus.ui.design.LocalWideLayout
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenBoardTheme
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.design.courseHues
import io.github.binoverfl0w.zencampus.ui.services.CatalogueBookScreen
import io.github.binoverfl0w.zencampus.ui.services.LibraryCatalogueScreen
import io.github.binoverfl0w.zencampus.ui.services.LibraryScreen
import io.github.binoverfl0w.zencampus.ui.services.LocalSavedSignIns
import io.github.binoverfl0w.zencampus.ui.services.MensaScreen
import io.github.binoverfl0w.zencampus.ui.services.PublicCampusApp
import io.github.binoverfl0w.zencampus.ui.services.SavedBooksScreen
import io.github.binoverfl0w.zencampus.ui.services.ServiceAccountsScreen
import io.github.binoverfl0w.zencampus.ui.study.AnnouncementComments
import io.github.binoverfl0w.zencampus.ui.study.AnnouncementDialog
import io.github.binoverfl0w.zencampus.ui.study.AssignmentFolderScreen
import io.github.binoverfl0w.zencampus.ui.study.AssignmentUploadDialog
import io.github.binoverfl0w.zencampus.ui.study.CalendarExportDialog
import io.github.binoverfl0w.zencampus.ui.study.CalendarScreen
import io.github.binoverfl0w.zencampus.ui.study.LocalCampusBrowser
import io.github.binoverfl0w.zencampus.ui.study.CourseCommunityScreen
import io.github.binoverfl0w.zencampus.ui.study.CourseScreen
import io.github.binoverfl0w.zencampus.ui.study.CoursesScreen
import io.github.binoverfl0w.zencampus.ui.study.CourseworkScreen
import io.github.binoverfl0w.zencampus.ui.study.CourseworkTaskScreen
import io.github.binoverfl0w.zencampus.ui.study.DownloadsScreen
import io.github.binoverfl0w.zencampus.ui.study.InboxScreen
import io.github.binoverfl0w.zencampus.ui.study.LocalAssignmentOpenUrl
import io.github.binoverfl0w.zencampus.ui.study.LocalIncomingShare
import io.github.binoverfl0w.zencampus.ui.study.OfficeHoursScreen
import io.github.binoverfl0w.zencampus.ui.study.ShareIntoFolderSheet
import io.github.binoverfl0w.zencampus.ui.study.TodayScreen
import io.github.binoverfl0w.zencampus.ui.study.UpdatesScreen
import io.github.binoverfl0w.zencampus.ui.study.courseworkAttention
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

@Composable
fun ZenApp(
    repository: CampusRepository?,
    settings: AppSettings,
    network: NetworkConditions,
    ready: Boolean,
    systemLanguage: String,
    onSignIn: () -> Unit,
    onSignOut: (Boolean) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBackHandler: ((() -> Unit)?) -> Unit = {},
    services: CampusServicesRepository? = null,
    onConnectLibrary: () -> Unit = {}, onDisconnectLibrary: (Boolean) -> Unit = {}, onLibraryReminders: (Boolean) -> Unit = {},
    serviceRequest: String = "",
    onRefreshHandler: ((() -> Unit)?) -> Unit = {},
    appVersion: String = "",
    onPreview: (() -> Unit)? = null,
    maintenance: AppMaintenance? = null,
    signInSession: CampusSession? = null,
    onAutomaticSignIn: () -> Unit = onSignIn,
    signInForeground: Boolean = false,
    signInConnected: Boolean = network.connected,
) {
    val german = settings.language == AppLanguage.GERMAN || (settings.language == AppLanguage.SYSTEM && systemLanguage.startsWith("de"))
    val words = remember(german) { Words(german) }
    var now by remember { mutableLongStateOf(nowMillis()) }
    var publicCampus by rememberSaveable { mutableStateOf(false) }
    var accountRequest by rememberSaveable { mutableStateOf("") }
    var setupAccounts by rememberSaveable { mutableStateOf(false) }
    val owner = repository?.account?.takeUnless { it.demo }?.cacheScope
    val savedSignIns = signInSession?.savedSignIns
    val savedEntries = savedSignIns?.entries?.collectAsState()?.value.orEmpty()
    if (owner != null && signInSession != null) {
        val expired = repository.sync.collectAsState().value.sessionExpired
        val active by rememberUpdatedState(signInForeground && signInConnected)
        val openLogin by rememberUpdatedState(onAutomaticSignIn)
        val hasStudyEntry = savedEntries.any { it.kind == SignInKind.STUDY }
        LaunchedEffect(repository, expired, hasStudyEntry, active) {
            if (active && expired && hasStudyEntry && signInSession.claimAutomaticSignIn(owner) { active } && active) openLogin()
        }
    }
    val openSetup: () -> Unit = { setupAccounts = true; publicCampus = true; accountRequest = "accounts#${nowMillis()}" }
    val signOut: (Boolean) -> Unit = { removeSaved ->
        setupAccounts = false; publicCampus = true; accountRequest = "accounts#${nowMillis()}"; onSignOut(removeSaved)
    }
    LaunchedEffect(serviceRequest) { if (serviceRequest.isNotBlank() && repository == null) { publicCampus = true; accountRequest = "" } }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = nowMillis() } }
    ZenTheme(settings.theme) {
        CompositionLocalProvider(LocalWords provides words, LocalNow provides now, LocalReducedMotion provides settings.reduceMotion,
            LocalAppVersion provides appVersion, LocalAppMaintenance provides maintenance,
            LocalSavedSignIns provides savedSignIns) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    !ready -> StartupScreen(onRefreshHandler)
                    repository == null -> {
                        if (publicCampus && services != null) PublicCampusApp(services, network, { publicCampus = false; setupAccounts = false; accountRequest = "" },
                            onConnectLibrary, onDisconnectLibrary, onLibraryReminders, onOpenUrl, onBackHandler,
                            accountRequest.ifBlank { serviceRequest }, onSignIn, setupAccounts, openSetup,
                            onCancelSetup = { setupAccounts = false; accountRequest = "" }, onRefreshHandler = onRefreshHandler)
                        else { SideEffect { onBackHandler(null); onRefreshHandler(null) }; WelcomeScreen(openSetup, { publicCampus = true; accountRequest = "" }, onPreview,
                            onSavedSignIns = if (savedSignIns == null || services == null) null else ({
                                publicCampus = true; accountRequest = "settings#${nowMillis()}"
                            })) }
                    }
                    // The saved data is drawn in the first frame, not empty cards that it replaces a moment later.
                    !repository.restored.collectAsState().value -> StartupScreen(onRefreshHandler)
                    else -> key(repository.account.cacheScope) {
                        val campusBrowser = LocalCampusBrowser.current
                        val demo = repository.account.demo
                        val openUrl: (String) -> Unit = remember(onOpenUrl, campusBrowser, demo) {
                            { url -> if (demo || campusBrowser?.invoke(url) != true) onOpenUrl(url) }
                        }
                        CampusApp(repository, settings, network, onSignIn, signOut, onSettings, openUrl, onBackHandler,
                            services, onConnectLibrary, onDisconnectLibrary, onLibraryReminders, serviceRequest,
                            setupAccounts, { setupAccounts = false; accountRequest = "" }, onRefreshHandler)
                    }
                }
                if (ready) ReleasePrompts(settings, onSettings)
            }
        }
    }
}

/** Same tile as the launcher icon and the launch screen, so startup hands over without a jump. */
@Composable
private fun StartupScreen(onRefreshHandler: ((() -> Unit)?) -> Unit) {
    SideEffect { onRefreshHandler(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center) {
                ZenIcon(Symbol.LEAF, Modifier.size(46.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(18.dp))
            Text(LocalWords.current.text("loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CampusApp(
    repository: CampusRepository, settings: AppSettings, network: NetworkConditions,
    onSignIn: () -> Unit, onSignOut: (Boolean) -> Unit, onSettings: (AppSettings) -> Unit,
    onOpenUrl: (String) -> Unit, onBackHandler: ((() -> Unit)?) -> Unit,
    services: CampusServicesRepository?, onConnectLibrary: () -> Unit, onDisconnectLibrary: (Boolean) -> Unit,
    onLibraryReminders: (Boolean) -> Unit, serviceRequest: String,
    setupAccounts: Boolean, onFinishSetup: () -> Unit,
    onRefreshHandler: ((() -> Unit)?) -> Unit,
) {
    val snapshot by repository.snapshot.collectAsState()
    val sync by repository.sync.collectAsState()
    val weatherLoading by repository.weatherLoading.collectAsState()
    val uploadDraft by repository.uploadDraft.collectAsState()
    val study by repository.studySync.collectAsState()
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val navigationSaver = remember { Saver<CampusNavigation, String>(
        save = { Json.encodeToString(it.prepared()) }, restore = { CampusNavigation.restore(it) }) }
    var navigation by rememberSaveable(stateSaver = navigationSaver) { mutableStateOf(CampusNavigation()) }
    LaunchedEffect(setupAccounts) { if (setupAccounts) navigation = navigation.openService("accounts") }
    var searching by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(serviceRequest) {
        val target = serviceRequest.substringBefore('#')
        if (target == "search") searching = true
        else if (serviceRequest.isNotBlank()) navigation = navigation.openService(target)
    }
    val destination = navigation.current
    val tab = destination.tab
    val route = destination.route
    var switcher by rememberSaveable { mutableStateOf(false) }
    val menuState = services?.mensa?.collectAsState()?.value
    val libraryState = services?.library?.collectAsState()?.value
    val serviceBusy = when {
        tab == "loans" -> libraryState?.let { it.loading || it.renewing != null } == true
        tab == "mensa" -> menuState?.loading == true
        else -> false
    }
    val folderPath = destination.folderPath
    val calendarJump = destination.calendarJump
    val weatherInterval = LocalNow.current / 60_000L
    LaunchedEffect(repository, tab, route, network.connected, settings.dataSaver, weatherInterval) {
        if (route == null && tab in setOf("today", "calendar")) repository.refreshWeather(visible = true)
    }
    LaunchedEffect(services, tab, route, network.connected, settings.dataSaver, weatherInterval) {
        if (tab == "today" && route == null && services != null) {
            services.refreshMensa(background = true)
            if (network.canPrefetch(settings)) services.refreshLibrary()
        }
    }
    var selectedAnnouncement by rememberSaveable { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    val goBack: () -> Unit = {
        if (selectedAnnouncement != null) selectedAnnouncement = null
        else {
            if (setupAccounts && route == "accounts") onFinishSetup()
            navigation = navigation.back()
        }
    }
    SideEffect { onBackHandler(if (navigation.canGoBack || selectedAnnouncement != null) goBack else null) }
    val openCourse: (String) -> Unit = { id -> navigation = navigation.openCourse(id) }
    /** Content a Study page reads beyond the shared sync: a thread, a task, a folder, the planner's extra calendars. */
    val studyPageRefresh: suspend () -> Unit = {
        when (val screen = destination.screen) {
            is Screen.Community -> repository.refreshCommunity(screen.courseId, screen.section, screen.itemId, true)
            is Screen.Task -> repository.refreshCourseworkDetail(screen.courseId, screen.taskId, true)
            is Screen.Course -> if (screen.tab == 2 && screen.folderPath.isNotBlank()) repository.refreshFolder(screen.id, screen.folderPath.substringAfterLast('|'), true)
                else repository.visitCourse(screen.id, true)
            Screen.Section(CampusBoard.STUDY, "messages") -> repository.refreshMessages(true, true)
            Screen.Section(CampusBoard.STUDY, "calendar") -> {
                val planner = repository.planner.snapshot.value
                val date = runCatching { kotlinx.datetime.LocalDate.parse(planner.settings.selectedDate) }.getOrNull() ?: campusToday()
                repository.preparePlannerWeek(date, true)
                if (planner.settings.publishedRooms) repository.planner.refreshRooms(true)
                val wanted = planner.plans.filter { it.enabled }.map { it.course to it.semesterId } +
                    planner.schedules.values.filter { it.course.id == planner.settings.overlayCourseId }.map { it.course to it.semesterId } + planner.settings.overlays.map { it.course to it.semesterId }
                wanted.distinctBy { it.first.id to it.second }.forEach { (course, id) ->
                    snapshot.semesters.firstOrNull { it.id == id }?.let { repository.planner.inspect(course, it, true) }
                }
            }
            else -> {}
        }
    }
    val refresh: () -> Unit = {
        scope.launch {
            if (route == null && tab == "mensa") services?.refreshMensa(force = true)
            else if (route == null && tab == "loans") services?.refreshLibrary(force = true)
            else if (route == null && tab == "catalogue") services?.catalogue?.search?.value?.let { state ->
                state.query?.let { services.catalogue.search(it, state.results?.page ?: 1, true) }
            }
            else {
                // Study has one refresh: pull to refresh brings all of it up to date, plus the page's own deeper content.
                repository.requestStudySync(force = true)
                studyPageRefresh()
            }
        }
        if (route == null && tab in setOf("today", "calendar")) scope.launch { repository.refreshWeather(force = true) }
        if (route == null && tab == "today" && services != null) {
            scope.launch { services.refreshMensa(force = true) }
            scope.launch { services.refreshLibrary(force = true) }
        }
    }
    SideEffect { onRefreshHandler(if (!destination.global && tab != "saved" && route != "book" && !study.running && !serviceBusy) refresh else null) }
    val now = LocalNow.current
    val wideRoutes = route == null && tab in setOf("today", "calendar")
    val status = studySyncLine(snapshot, study, network).takeIf { destination.board == CampusBoard.STUDY && !destination.global }
        ?: if (snapshot.account.demo && !destination.global) SyncLine(words.service("sample_services")) else null
    val canRefresh = !destination.global && tab != "saved" && route != "book"
    val dueCount = remember(snapshot, now / 60_000) { courseworkAttention(snapshot, now).size }
    val unread = snapshot.messages["inbox"].orEmpty().count { !it.read }
    val hues = remember(snapshot.courses) { courseHues(snapshot.courses.map { it.id }) }
    var exportingCalendar by rememberSaveable { mutableStateOf(false) }
    val upcomingBookings = upcomingBookings(snapshot.consultationBookings, now)
    // Secondary destinations: shown in the rail, the board sheet and quick search instead of new bottom tabs.
    val places = boardPlaces(destination, dueCount, upcomingBookings.size, libraryState?.account?.holds?.count { it.ready } ?: 0) { place ->
        when (place) {
            BoardPlace.COURSEWORK -> navigation = navigation.openCoursework()
            BoardPlace.UPDATES -> navigation = navigation.openInbox("updates")
            BoardPlace.OFFICE_HOURS -> navigation = navigation.openOfficeHours()
            BoardPlace.DOWNLOADS -> navigation = navigation.openDownloads()
            BoardPlace.CALENDAR_EXPORT -> exportingCalendar = true
            BoardPlace.RESERVATIONS -> navigation = navigation.openTab("loans")
        }
    }
    CompositionLocalProvider(LocalCourseHues provides hues) {
    ZenBoardTheme(destination.board) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= Zen.wideBreakpoint
        CompositionLocalProvider(LocalWideLayout provides wide) {
        BoardSlider(destination.board, { board -> navigation = navigation.switchBoard(board) },
            Modifier.fillMaxSize(), enabled = !wide && !switcher && selectedAnnouncement == null && !setupAccounts && uploadDraft == null) {
            CampusShell(wide, fullWidth = wideRoutes,
                rail = {
                    CampusRail(destination, { navigation = navigation.switchBoard(it) }, { navigation = navigation.openTab(it) },
                        onCards = { navigation = navigation.openCards() }, onSettings = { navigation = navigation.openSettings() },
                        links = places,
                        sectionBadges = if (unread > 0) mapOf("messages" to "$unread") else emptyMap(),
                        pinned = if (destination.board == CampusBoard.STUDY) snapshot.courses.filter { it.id in snapshot.preferences.pinnedCourses } else emptyList(),
                        onCourse = openCourse,
                        status = status, onRefresh = if (canRefresh) refresh else null,
                        refreshEnabled = !serviceBusy, refreshing = if (destination.board == CampusBoard.STUDY) study.running else serviceBusy,
                        onSearch = { searching = true })
                },
                topBar = {
                    if (wide) WideTopBar(destination, if (navigation.canGoBack) goBack else null)
                    else BoardTopBar(destination, { switcher = true }, { navigation = navigation.openCards() },
                        onBack = if (navigation.canGoBack) goBack else null,
                        onRefresh = if (canRefresh) refresh else null,
                        refreshEnabled = !serviceBusy,
                        onSettings = { navigation = navigation.openSettings() },
                        status = status, refreshing = if (destination.board == CampusBoard.STUDY) study.running else serviceBusy,
                        onSearch = { searching = true })
                    if (destination.board == CampusBoard.STUDY && !destination.global) CampusContentFrame {
                        SyncErrorBanner(sync, onSignIn, refresh, { repository.dismissErrors() })
                    }
                },
                bottomBar = { BoardSections(destination, if (unread > 0) mapOf("messages" to "$unread") else emptyMap()) { navigation = navigation.openTab(it) } },
                onRefresh = if (canRefresh && !wide) refresh else null,
                refreshing = if (destination.board == CampusBoard.STUDY) study.running else serviceBusy,
            ) {
                        when (val screen = destination.screen) {
                            is Screen.OfficeHours -> holder.SaveableStateProvider("officehours:${screen.courseId}") {
                                OfficeHoursScreen(snapshot, repository, sync, screen.courseId, network.connected)
                            }
                            Screen.Accounts -> if (services != null) holder.SaveableStateProvider("accounts") {
                                ServiceAccountsScreen(snapshot.account, sync.sessionExpired, services, onSignIn, onSignOut,
                                    onConnectLibrary, onDisconnectLibrary, setupAccounts, { onFinishSetup(); navigation = navigation.openTab("today") },
                                    { navigation = navigation.switchBoard(it) })
                            }
                            is Screen.Book -> if (services != null) holder.SaveableStateProvider("book:${screen.id}") {
                                CatalogueBookScreen(services, screen.id, network, onOpenUrl)
                            }
                            Screen.Settings -> holder.SaveableStateProvider("settings") {
                                SettingsScreen(snapshot, repository, settings, onSettings,
                                    { navigation = navigation.openDownloads() }, { navigation = navigation.openCards() })
                            }
                            Screen.Downloads -> holder.SaveableStateProvider("downloads") { DownloadsScreen(snapshot, repository, sync) }
                            Screen.Coursework -> holder.SaveableStateProvider("coursework") {
                                CourseworkScreen(snapshot, repository, sync,
                                    { courseId, taskId -> navigation = navigation.openTask(courseId, taskId) },
                                    { navigation = navigation.openCourse(it).openCourseTab(3) })
                            }
                            is Screen.Assignment -> holder.SaveableStateProvider("assignment:${screen.folderId}") {
                                AssignmentFolderScreen(screen.courseId, screen.folderId, snapshot, repository, sync, onOpenUrl,
                                    { navigation = navigation.openCourse(screen.courseId).openCourseTab(2) })
                            }
                            is Screen.Task -> holder.SaveableStateProvider("task:${screen.courseId}:${screen.taskId}") {
                                CourseworkTaskScreen(screen.courseId, screen.taskId, snapshot, repository, sync,
                                    openCourse, onOpenUrl)
                            }
                            Screen.Updates -> UpdatesScreen(snapshot, repository, sync,
                                { selectedAnnouncement = it },
                                { navigation = navigation.push(CampusDestination(tab = "messages")) },
                                { courseId, section, discussionId -> navigation = navigation.openCommunity(courseId, section, discussionId) }, openCourse)
                            is Screen.Community -> {
                                val course = snapshot.courses.firstOrNull { it.id == screen.courseId }
                                if (course != null) CourseCommunityScreen(course, screen.section, screen.itemId,
                                    snapshot, repository, sync,
                                    { item ->
                                        if (screen.section == "people" && item == "__groups__") navigation = navigation.openCommunity(course.id, "groups")
                                        else navigation = navigation.openCommunity(course.id, screen.section, item)
                                    },
                                    {
                                        onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN
                                            else communityOfficialUrl(course.id, screen.section, snapshot.courseTools[course.id]?.sections.orEmpty()))
                                    }, onOpenUrl)
                            }
                            is Screen.Course -> {
                                val id = screen.id
                                val course = snapshot.courses.firstOrNull { it.id == id }
                                if (course != null) holder.SaveableStateProvider("course:$id") {
                                    CourseScreen(course, snapshot, repository, sync, screen.tab,
                                        { navigation = navigation.openCourseTab(it) }, folderPath,
                                        { navigation = navigation.openFolder(it) },
                                        { navigation = navigation.jumpToFolder(it) }, { navigation = navigation.openTab("courses") },
                                        { selectedAnnouncement = it }, onOpenUrl,
                                        { courseId, taskId -> navigation = navigation.openTask(courseId, taskId) },
                                        { courseId, section ->
                                            navigation = if (section == "officehours") navigation.openOfficeHours(courseId) else navigation.openCommunity(courseId, section)
                                        })
                                } else Column(Modifier.padding(24.dp)) {
                                    EmptyMoment(words.text("no_courses"), words.text("error_not_found"))
                                }
                            }
                            is Screen.Section -> holder.SaveableStateProvider("${screen.board}:${screen.tab}") {
                                when (screen.tab) {
                                    "mensa" -> if (services != null) MensaScreen(services, network, onOpenUrl)
                                    "loans" -> if (services != null) LibraryScreen(services, network, onLibraryReminders, onOpenUrl)
                                    "catalogue" -> if (services != null) LibraryCatalogueScreen(services, network, { navigation = navigation.openBook(it) }, onOpenUrl,
                                        destination.catalogueQuery)
                                    "saved" -> if (services != null) SavedBooksScreen(services) { navigation = navigation.openBook(it) }
                                    "courses" -> CoursesScreen(snapshot, repository, sync, openCourse, onOpenUrl)
                                    "messages" -> InboxScreen(snapshot, repository, sync, onOpenUrl, destination.inboxView,
                                        { view -> navigation = navigation.openInbox(view) },
                                        { selectedAnnouncement = it },
                                        { courseId, section, discussionId -> navigation = navigation.openCommunity(courseId, section, discussionId) }, openCourse)
                                    "calendar" -> CalendarScreen(snapshot, repository, sync, calendarJump, openCourse, weatherLoading, network.connected, onOpenUrl)
                                    else -> TodayScreen(snapshot, sync, openCourse, { navigation = navigation.openCalendar() },
                                        { date -> navigation = navigation.openCalendar(date.toString()) },
                                        { selectedAnnouncement = it }, weatherLoading, onOpenUrl, services,
                                        { navigation = navigation.openService(it) }, { navigation = navigation.openCoursework() },
                                        { courseId, taskId -> navigation = navigation.openTask(courseId, taskId) },
                                        { navigation = navigation.openUpdates() },
                                        { navigation = navigation.openCourse(it).openCourseTab(2) })
                                }
                            }
                        }
            }
        }
        }
        }
        CompositionLocalProvider(LocalAssignmentOpenUrl provides onOpenUrl) { AssignmentUploadDialog(repository, snapshot, sync) }
        val share = LocalIncomingShare.current
        if (share != null && uploadDraft == null) ShareIntoFolderSheet(share, repository, snapshot, sync)
        if (searching) QuickSearchDialog(snapshot, { searching = false }, openCourse, { repository.requestMaterial(it) },
            { query -> if (services != null) navigation = navigation.searchCatalogue(query) }, places)
        if (switcher) BoardSwitcher(destination.board, snapshot.account, libraryState ?: LibraryState(), { switcher = false },
            { navigation = navigation.switchBoard(it) }, { navigation = navigation.openCards() }, places)
        if (exportingCalendar) CalendarExportDialog(repository, sync) { exportingCalendar = false }
        snapshot.announcements.firstOrNull { it.id == selectedAnnouncement }?.let { announcement ->
            AnnouncementDialog(announcement, snapshot.courses.firstOrNull { it.id == announcement.courseId },
                { selectedAnnouncement = null }, { scope.launch { repository.markRead(announcement.id) }; selectedAnnouncement = null }) {
                AnnouncementComments(announcement, snapshot, repository, sync)
            }
        }
    }
    }
}

