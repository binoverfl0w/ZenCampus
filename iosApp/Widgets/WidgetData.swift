import WidgetKit
import Foundation

struct HomeWidgets: Decodable {
    let signedIn: Bool
    let date: String
    let weather: String?
    let nextClass: Lesson?
    let due: [Deadline]
    let dueCount: Int
    let mensa: Mensa
    let library: Library
    let labels: Labels
    let refreshAt: Int64

    struct Lesson: Decodable {
        let initials, title: String
        let running: Bool
        let start, range, day, location: String

        var end: String { range.components(separatedBy: " – ").last ?? range }
    }

    struct Deadline: Decodable {
        let initials, title, course, due: String
        let urgent: Bool
    }

    struct Mensa: Decodable {
        let meals: [Meal]
        let total: Int
        let status: String?
        struct Meal: Decodable {
            let name, price: String
            let favorite: Bool
            let diet: String?
            var title: String { favorite ? "★ " + name : name }
        }
    }

    struct Library: Decodable {
        let connected: Bool
        let headline, detail: String
        let attention: Bool
    }

    struct Labels: Decodable {
        let now, nextClass, until, due, nothingDue, noClasses, mensa, library, signIn, more: String

        func count(_ count: Int) -> String {
            count > 0 ? more.replacingOccurrences(of: "%d", with: String(count)) : ""
        }
    }
}

struct WidgetFrame: Decodable, TimelineEntry {
    private let at: Int64
    let content: HomeWidgets?

    var date: Date { Date(timeIntervalSince1970: Double(at) / 1000) }

    init(date: Date, content: HomeWidgets?) {
        self.at = Int64(date.timeIntervalSince1970 * 1000)
        self.content = content
    }
}

struct HomeTimeline: TimelineProvider {
    func placeholder(in context: Context) -> WidgetFrame { WidgetFrame(date: Date(), content: nil) }

    func getSnapshot(in context: Context, completion: @escaping (WidgetFrame) -> Void) {
        completion(entries().first ?? placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WidgetFrame>) -> Void) {
        // The app requests a reload whenever it publishes a fresh copy.
        completion(Timeline(entries: entries(), policy: .never))
    }

    private func entries() -> [WidgetFrame] {
        let now = Date()
        guard let group = Bundle.main.object(forInfoDictionaryKey: "ZenWidgetGroup") as? String,
              let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let data = try? Data(contentsOf: directory.appendingPathComponent("home-widgets.json")),
              let frames = try? JSONDecoder().decode([WidgetFrame].self, from: data),
              let last = frames.last?.content else {
            return [WidgetFrame(date: now, content: nil)]
        }
        let expires = Date(timeIntervalSince1970: Double(last.refreshAt) / 1000)
        guard expires > now else { return [WidgetFrame(date: now, content: nil)] }
        let current = frames.last { $0.date <= now }
        return [WidgetFrame(date: now, content: current?.content)] +
            frames.filter { $0.date > now } + [WidgetFrame(date: expires, content: nil)]
    }
}
