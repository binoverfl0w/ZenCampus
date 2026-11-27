import SwiftUI
import WidgetKit

private enum WidgetKind: String {
    case day, next, mensa, due, library

    var title: LocalizedStringKey {
        switch self {
        case .day: "Your day"
        case .next: "Next class"
        case .mensa: "Mensa today"
        case .due: "Due soon"
        case .library: "Library"
        }
    }

    var sizes: [WidgetFamily] {
        switch self {
        case .day, .due: [.systemMedium]
        case .next, .mensa, .library: [.systemSmall]
        }
    }

    var description: LocalizedStringKey {
        switch self {
        case .day: "Next class, the next deadline and today's Mensa"
        case .next: "When and where your next class is"
        case .mensa: "Today's dishes and prices, favourites first"
        case .due: "Open coursework deadlines this week"
        case .library: "Loans due and books ready to pick up"
        }
    }

    func route(_ model: HomeWidgets?) -> String {
        switch self {
        case .day: "today"
        case .next: model?.signedIn == true ? "calendar" : "accounts"
        case .due: model?.signedIn == true ? "coursework" : "accounts"
        case .mensa: "mensa"
        case .library: model?.library.connected == true ? "library" : "accounts"
        }
    }
}

private protocol WidgetStyle {
    static var kind: WidgetKind { get }
}

private enum DayStyle: WidgetStyle { static let kind = WidgetKind.day }
private enum NextStyle: WidgetStyle { static let kind = WidgetKind.next }
private enum MensaStyle: WidgetStyle { static let kind = WidgetKind.mensa }
private enum DueStyle: WidgetStyle { static let kind = WidgetKind.due }
private enum LibraryStyle: WidgetStyle { static let kind = WidgetKind.library }

private struct CampusWidget<Style: WidgetStyle>: Widget {
    private var kind: WidgetKind { Style.kind }

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind.rawValue, provider: HomeTimeline()) { frame in
            CampusWidgetView(kind: kind, data: frame.content)
        }
        .configurationDisplayName(kind.title)
        .description(kind.description)
        .supportedFamilies(kind.sizes)
        .contentMarginsDisabled()
    }
}

@main
struct ZenCampusWidgets: WidgetBundle {
    var body: some Widget {
        CampusWidget<DayStyle>()
        CampusWidget<NextStyle>()
        CampusWidget<MensaStyle>()
        CampusWidget<DueStyle>()
        CampusWidget<LibraryStyle>()
    }
}

private struct CampusWidgetView: View {
    let kind: WidgetKind
    let data: HomeWidgets?
    @Environment(\.colorScheme) private var scheme

    private enum Tone { case study, mensa, library, alert }
    private var text: Color { color(0x1D2428, 0xE7ECEF) }
    private var muted: Color { color(0x56616A, 0xADB9C0) }
    private var surface: Color { color(0xFBFAF7, 0x1A2025) }
    private var background: Color {
        switch kind {
        case .next: tint(.study)
        case .mensa: tint(.mensa)
        case .library: tint(.library)
        case .day, .due: surface
        }
    }

    var body: some View {
        Group {
            if let data {
                switch kind {
                case .day: day(data)
                case .next: next(data)
                case .mensa: mensa(data)
                case .due: due(data)
                case .library: library(data)
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text(kind.title).font(.custom("Manrope-SemiBold", size: 14))
                    Text("Open ZenCampus to update").font(.custom("Manrope-Regular", size: 12))
                }
            }
        }
        .foregroundStyle(text)
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .containerBackground(for: .widget) { background }
        .widgetURL(url(kind.route(data)))
    }

    private func url(_ route: String) -> URL? {
        guard let scheme = Bundle.main.object(forInfoDictionaryKey: "ZenWidgetURLScheme") as? String else { return nil }
        return URL(string: "\(scheme)://open/\(route)")
    }

    @ViewBuilder
    private func routeLink<Content: View>(_ route: String, @ViewBuilder content: () -> Content) -> some View {
        if let destination = url(route) { Link(destination: destination, label: content) }
        else { content() }
    }

    private func day(_ model: HomeWidgets) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            routeLink("today") {
                HStack {
                    label(model.date, 13, bold: true)
                    Spacer()
                    if let weather = model.weather { label(weather, 11).foregroundStyle(muted) }
                }
                .contentShape(Rectangle())
            }
            let lesson = model.nextClass
            routeLink(model.signedIn ? "calendar" : "accounts") {
                row(lesson?.initials ?? "·", lesson?.title ?? classFallback(model),
                    detail(lesson?.range, lesson?.location), tone: .study, chip: lesson?.day)
            }
            if model.signedIn {
                let item = model.due.first
                routeLink("coursework") {
                    row(item?.initials ?? "✓", item?.title ?? model.labels.nothingDue,
                        detail(item?.due, model.labels.count(model.dueCount - 1)),
                        tone: item?.urgent == true ? .alert : .study)
                }
            }
            let meal = model.mensa.meals.first
            routeLink("mensa") {
                row("●", meal?.title ?? model.mensa.status ?? model.labels.mensa,
                    meal.map { detail($0.price, model.labels.count(model.mensa.total - 1)) } ?? model.labels.mensa,
                    tone: .mensa)
            }
        }
    }

    private func next(_ model: HomeWidgets) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let lesson = model.nextClass
            label(lesson?.running == true ? model.labels.now : model.labels.nextClass, 11, bold: true)
                .foregroundStyle(accent(.study))
            if let lesson {
                label(lesson.running ? lesson.end : lesson.start, 29, bold: true)
                let when = lesson.running ? "\(model.labels.until) \(lesson.end)" : lesson.day
                label(detail(when, lesson.location), 11).foregroundStyle(muted)
            }
            label(lesson?.title ?? classFallback(model), 14, bold: true, lines: 2)
            if let lesson, !lesson.running {
                label(lesson.range, 10).foregroundStyle(muted)
            }
        }
    }

    private func mensa(_ model: HomeWidgets) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            label(model.labels.mensa, 13, bold: true).foregroundStyle(accent(.mensa))
            let meals = Array(model.mensa.meals.prefix(4))
            ForEach(meals.indices, id: \.self) { index in
                let meal = meals[index]
                if index > 0 { Divider().overlay(color(0xE6E5E0, 0x283037)) }
                HStack(spacing: 3) {
                    if let diet = meal.diet {
                        Circle().fill(accent(.library)).frame(width: 5, height: 5).accessibilityLabel(diet)
                    }
                    label(meal.title, 10, bold: true)
                    Spacer(minLength: 2)
                    label(meal.price, 10).fixedSize()
                }
            }
            if meals.isEmpty { label(model.mensa.status ?? "", 13, lines: 2) }
            if model.mensa.total > meals.count {
                label(model.labels.count(model.mensa.total - meals.count), 10).foregroundStyle(muted)
            }
        }
    }

    private func due(_ model: HomeWidgets) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let title = model.dueCount > 0 ? "\(model.labels.due) · \(model.dueCount)" : model.labels.due
            label(title, 13, bold: true).foregroundStyle(accent(.study))
            let items = Array(model.due.prefix(3))
            ForEach(items.indices, id: \.self) { index in
                let item = items[index]
                row(item.initials, item.title, item.course, tone: item.urgent ? .alert : .study,
                    chip: item.due, tinted: false)
            }
            if items.isEmpty { label(model.signedIn ? model.labels.nothingDue : model.labels.signIn, 14) }
            if model.dueCount > items.count {
                label(model.labels.count(model.dueCount - items.count), 10).foregroundStyle(muted)
            }
        }
    }

    private func library(_ model: HomeWidgets) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            label(model.labels.library, 13, bold: true).foregroundStyle(accent(.library))
            label(model.library.headline, 15, bold: true, lines: 3)
            if !model.library.detail.isEmpty {
                label(model.library.detail, 11, lines: 2)
                    .foregroundStyle(model.library.attention ? accent(.alert) : muted)
            }
        }
    }

    private func row(_ initials: String, _ title: String, _ detail: String, tone: Tone,
                     chip: String? = nil, tinted: Bool = true) -> some View {
        HStack(spacing: 6) {
            label(initials, 10, bold: true)
                .foregroundStyle(tinted ? surface : accent(tone))
                .frame(width: 25, height: 25)
                .background(tinted ? accent(tone) : tint(tone), in: RoundedRectangle(cornerRadius: 7))
            VStack(alignment: .leading, spacing: 0) {
                label(title, 11, bold: true)
                if !detail.isEmpty { label(detail, 10).foregroundStyle(muted) }
            }
            Spacer(minLength: 0)
            if let chip {
                label(chip, 10, bold: true).foregroundStyle(accent(tone))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(tinted ? surface : tint(tone), in: Capsule())
            }
        }
        .padding(.horizontal, 5).padding(.vertical, 3)
        .background(tinted ? tint(tone) : surface, in: RoundedRectangle(cornerRadius: 9))
    }

    private func classFallback(_ model: HomeWidgets) -> String {
        model.signedIn ? model.labels.noClasses : model.labels.signIn
    }

    private func detail(_ pieces: String?...) -> String {
        pieces.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func label(_ value: String, _ size: CGFloat, bold: Bool = false, lines: Int = 1) -> some View {
        Text(value).font(.custom(bold ? "Manrope-SemiBold" : "Manrope-Regular", size: size))
            .lineLimit(lines)
    }

    private func accent(_ tone: Tone) -> Color {
        switch tone {
        case .study: color(0x355F7A, 0x9EC4DB)
        case .mensa: color(0x7A5A39, 0xDDC3A2)
        case .library: color(0x46664F, 0xB8CEBA)
        case .alert: color(0x9B514B, 0xE7B7AF)
        }
    }

    private func tint(_ tone: Tone) -> Color {
        switch tone {
        case .study: color(0xDDE8EF, 0x22384A)
        case .mensa: color(0xF2E4D0, 0x51402E)
        case .library: color(0xDDEADF, 0x344B3A)
        case .alert: color(0xF5E3DF, 0x51332F)
        }
    }

    private func color(_ light: UInt32, _ dark: UInt32) -> Color {
        let rgb = scheme == .dark ? dark : light
        return Color(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255,
                     blue: Double(rgb & 255) / 255)
    }
}
