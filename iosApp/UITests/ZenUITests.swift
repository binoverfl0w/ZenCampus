import XCTest
final class ZenUITests: XCTestCase {
    private func selectSavedSignIn(_ app: XCUIApplication, _ username: String) {
        let chooser = app.buttons["Sign in as…"]
        XCTAssertTrue(chooser.waitForExistence(timeout: 20))
        chooser.tap()
        let entry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Sign in as " + username + ",")).firstMatch
        guard entry.waitForExistence(timeout: 5) else { return XCTFail("The saved account was absent from the chooser") }
        evidence(username.contains("@") ? "study-chooser" : "library-chooser")
        entry.tap()
    }
    private func addSavedSignIn(_ app: XCUIApplication, field: String, username: String) {
        app.buttons["Add account"].tap()
        let name = app.textFields[field]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        evidence(field == "University email" ? "study-add-account" : "library-add-account")
        name.tap()
        name.typeText(username)
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText("New fixture password")
        app.buttons["Save"].tap()
    }
    func testAccountPickerForStudyStopsAtTheFixturePasswordError() {
        let app = XCUIApplication()
        app.launchArguments += ["--zen-ui-preview", "--zen-sign-in-fixture"]
        app.launch()
        XCTAssertTrue(app.webViews.staticTexts["Fixture sign-in page"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.webViews.staticTexts["Fixture received one password submission"].exists)
        selectSavedSignIn(app, "student@example.test")
        XCTAssertTrue(app.webViews.staticTexts["Fixture received one password submission"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.webViews.staticTexts["Fixture password was rejected"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Edit saved sign-in"].exists)
        XCTAssertFalse(app.webViews.staticTexts["Unexpected fixture submission"].exists)
        app.webViews.buttons["Reset fixture form"].tap()
        addSavedSignIn(app, field: "University email", username: "new@example.test")
        XCTAssertTrue(app.webViews.staticTexts["Fixture received added account"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Sign in as new@example.test"].exists)
    }
    func testAccountPickerForLibraryKeepsTheSecondFactorUntouched() {
        let app = XCUIApplication()
        app.launchArguments += ["--zen-ui-preview", "--zen-sign-in-fixture", "--zen-library-fixture"]
        app.launch()
        XCTAssertTrue(app.webViews.staticTexts["Fixture library page"].waitForExistence(timeout: 20))
        selectSavedSignIn(app, "12345")
        XCTAssertTrue(app.webViews.staticTexts["Library received selected account"].waitForExistence(timeout: 5))
        addSavedSignIn(app, field: "Library number", username: "65432")
        XCTAssertTrue(app.webViews.staticTexts["Library received added account"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.webViews.staticTexts["Unexpected fixture submission"].exists)
    }
    private func preview(_ app: XCUIApplication) {
        app.launchArguments.append("--zen-ui-preview")
        app.launch()
        let preview = app.buttons["Preview mode"]
        XCTAssertTrue(preview.waitForExistence(timeout: 30)); preview.tap()
        XCTAssertTrue(app.buttons["section-courses"].waitForExistence(timeout: 30))
    }
    private func materials(_ app: XCUIApplication) {
        preview(app); app.buttons["section-courses"].tap()
        XCTAssertTrue(app.staticTexts["Algorithmen und Datenstrukturen"].waitForExistence(timeout: 20))
        app.staticTexts["Algorithmen und Datenstrukturen"].firstMatch.tap()
        XCTAssertTrue(app.buttons["course-tab-materials"].waitForExistence(timeout: 15))
        app.buttons["course-tab-materials"].tap()
        XCTAssertTrue(app.buttons["choose-material-folder"].waitForExistence(timeout: 15))
    }
    func testFolderPickerAndSync() {
        let app = XCUIApplication(); materials(app)
        // A default sync creates this app's own Files container on a clean simulator.
        app.buttons["sync-course-materials"].tap()
        let firstConfirm = app.buttons["confirm-material-sync"]
        XCTAssertTrue(firstConfirm.waitForExistence(timeout: 30)); firstConfirm.tap()
        let initialResult = app.staticTexts["sync-result"]
        XCTAssertTrue(initialResult.waitForExistence(timeout: 60))
        XCTAssertTrue(initialResult.waitForNonExistence(timeout: 10), "A clean sync should close automatically")
        XCTAssertTrue(app.buttons["choose-material-folder"].waitForExistence(timeout: 15))
        app.buttons["choose-material-folder"].tap()
        let browse = app.buttons["Browse"]
        XCTAssertTrue(browse.waitForExistence(timeout: 30)); browse.tap()
        let onPhone = app.cells["DOC.sidebar.item.On My iPhone"]
        if onPhone.waitForExistence(timeout: 5) { onPhone.tap() }
        let folder = app.cells.matching(NSPredicate(format: "label == 'ZenCampus' OR label BEGINSWITH 'ZenCampus,'")).firstMatch
        XCTAssertTrue(folder.waitForExistence(timeout: 20)); folder.tap()
        let open = app.buttons["DOCPicker.actionButton"]
        XCTAssertTrue(open.waitForExistence(timeout: 20)); open.tap()
        let reset = app.buttons["Use app folder"]
        XCTAssertTrue(reset.waitForExistence(timeout: 20), "A security-scoped folder should be saved")
        app.buttons["sync-course-materials"].tap()
        let confirm = app.buttons["confirm-material-sync"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 30)); confirm.tap()
        let result = app.staticTexts["sync-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 60)); print("ZEN_SYNC_RESULT \(result.label)")
        XCTAssertFalse(result.label.hasPrefix("0 "), "At least one sample file should be written")
    }
    func testVipsSample() {
        let app = XCUIApplication(); materials(app); app.swipeUp(); sleep(3)
        let sheet = app.buttons["vips-sheet-preview-3"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 20)); sheet.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 15))
        let exercise = app.links.matching(NSPredicate(format: "label BEGINSWITH '1. Breadth-first search'")).firstMatch
        XCTAssertTrue(exercise.waitForExistence(timeout: 10)); exercise.tap()
        XCTAssertTrue(app.staticTexts["Read-only sample."].waitForExistence(timeout: 15))
        let pdf = app.links["Graphs - Exercise sheet 3.pdf"]
        XCTAssertTrue(pdf.waitForExistence(timeout: 10)); pdf.tap()
        XCTAssertTrue(app.otherElements["QLPDFViewControllerViewAccessibilityIdentifier"].waitForExistence(timeout: 20))
    }
    func testQuickActionOpensCalendar() throws {
        let app = XCUIApplication(); preview(app)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        XCUIDevice.shared.press(.home)
        let icons = springboard.icons.matching(identifier: "ZenCampus")
        func visibleIcon() -> XCUIElement? {
            icons.allElementsBoundByIndex.first { !$0.frame.isEmpty && $0.isHittable }
        }
        var icon = visibleIcon()
        for _ in 0..<4 where icon == nil {
            springboard.swipeLeft()
            icon = visibleIcon()
        }
        try XCTUnwrap(icon, "ZenCampus should be visible on the Home Screen or in App Library").press(forDuration: 1.2)
        let calendar = menuItem("Calendar", in: springboard)
        XCTAssertTrue(calendar.waitForExistence(timeout: 10))
        evidence("quick-actions")
        calendar.tap()
        XCTAssertTrue(app.otherElements["planner-calendar"].waitForExistence(timeout: 15))
    }

    func testIncomingFileReview() throws {
        guard #available(iOS 26, *) else { throw XCTSkip("Files' Open With menu requires iOS 26.") }
        let app = XCUIApplication(); preview(app)
        let files = XCUIApplication(bundleIdentifier: "com.apple.DocumentsApp")
        files.launch()
        let browse = files.buttons["Browse"]
        XCTAssertTrue(browse.waitForExistence(timeout: 20)); browse.tap()
        let onPhone = files.cells["DOC.sidebar.item.On My iPhone"]
        if onPhone.waitForExistence(timeout: 5) { onPhone.tap() }
        let file = files.cells.matching(NSPredicate(format: "label BEGINSWITH 'Sample submission'")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 15)); file.press(forDuration: 1)
        let openWith = menuItem("Open With", in: files)
        XCTAssertTrue(openWith.waitForExistence(timeout: 10)); openWith.tap()
        let zen = menuItem("ZenCampus", in: files)
        XCTAssertTrue(zen.waitForExistence(timeout: 10))
        evidence("share-open-with")
        zen.tap()

        let title = app.staticTexts["Hand in a file"]
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        let folder = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'share-folder-'")).firstMatch
        XCTAssertTrue(folder.waitForExistence(timeout: 30))
        evidence("share-folders")
        folder.tap()
        XCTAssertTrue(app.buttons["confirm-assignment-upload"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Sample submission.txt"].exists)
        evidence("share-review")
    }

    private func menuItem(_ title: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ AND (elementType == %d OR elementType == %d)",
            title, XCUIElement.ElementType.cell.rawValue, XCUIElement.ElementType.button.rawValue)).firstMatch
    }

    private func evidence(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testBackgroundAndNotificationPermission() {
        let app = XCUIApplication(); preview(app)
        app.buttons["Settings"].tap(); app.swipeUp(); sleep(3)
        let background = app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Background refresh'")).firstMatch
        XCTAssertTrue(background.waitForExistence(timeout: 20))
        print("ZEN_BACKGROUND_BEFORE value=\(String(describing: background.value))")
        let notice = app.buttons["OK"]
        if (background.value as? String) != "On" {
            background.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
            if notice.waitForExistence(timeout: 3) { notice.tap() }
            if background.exists && (background.value as? String) != "On" {
                background.coordinate(withNormalizedOffset: CGVector(dx: 0.90, dy: 0.5)).tap()
                if notice.waitForExistence(timeout: 3) { notice.tap() }
            }
        }
        sleep(1)
        if notice.waitForExistence(timeout: 2) { notice.tap() }
        print("ZEN_BACKGROUND_AFTER value=\(String(describing: background.value))")
        XCTAssertEqual(background.value as? String, "On")
        app.swipeDown(); sleep(3)
        let coursework = app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Coursework reminders'")).firstMatch
        XCTAssertTrue(coursework.waitForExistence(timeout: 20))
        if (coursework.value as? String) != "On" {
            coursework.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        sleep(1)
        if notice.waitForExistence(timeout: 2) { notice.tap() }
        XCTAssertEqual(coursework.value as? String, "On")
    }
}
