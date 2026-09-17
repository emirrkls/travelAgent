import SwiftUI

enum PhokartaLanguage: String, CaseIterable {
    case system, en, tr

    var locale: Locale? { self == .system ? nil : Locale(identifier: rawValue) }
    var localizationKey: String { "settings.language.\(rawValue)" }
}

func phokartaString(_ key: String, locale: Locale) -> String {
    String(localized: String.LocalizationValue(key), locale: locale)
}

enum PhokartaSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 48
}

enum PhokartaRadius {
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 18
    static let xl: CGFloat = 24
}

enum PhokartaColor {
    // Brand reference: Android ui/theme/Theme.kt. Names are retained for source compatibility.
    static let coral = Color(red: 94 / 255, green: 182 / 255, blue: 236 / 255)
    static let coralDark = Color(red: 59 / 255, green: 149 / 255, blue: 204 / 255)
    static let ink = Color(red: 23 / 255, green: 33 / 255, blue: 43 / 255)
    static let sand = Color(red: 248 / 255, green: 251 / 255, blue: 253 / 255)
    static let sage = Color(red: 25 / 255, green: 115 / 255, blue: 111 / 255)
    static let mist = Color(red: 221 / 255, green: 242 / 255, blue: 1)
    static let muted = Color(red: 101 / 255, green: 113 / 255, blue: 125 / 255)
    static let border = Color(red: 220 / 255, green: 229 / 255, blue: 235 / 255)
    static let divider = Color(red: 234 / 255, green: 240 / 255, blue: 244 / 255)
    static let selectedSurface = Color(red: 231 / 255, green: 245 / 255, blue: 1)
    static let surfaceSoft = Color(red: 241 / 255, green: 246 / 255, blue: 249 / 255)
    static let surfaceLight = Color.white
    static let backgroundDark = Color(red: 16 / 255, green: 24 / 255, blue: 32 / 255)
    static let surfaceDark = Color(red: 23 / 255, green: 35 / 255, blue: 48 / 255)
    static let surfaceSoftDark = Color(red: 29 / 255, green: 43 / 255, blue: 56 / 255)
    static let selectedSurfaceDark = Color(red: 33 / 255, green: 58 / 255, blue: 73 / 255)
    static let borderDark = Color(red: 48 / 255, green: 65 / 255, blue: 78 / 255)
    static let onDark = Color(red: 244 / 255, green: 248 / 255, blue: 250 / 255)
    static let coralDarkMode = Color(red: 142 / 255, green: 205 / 255, blue: 244 / 255)

    static func background(for scheme: ColorScheme) -> Color {
        scheme == .dark ? backgroundDark : sand
    }

    static func surface(for scheme: ColorScheme) -> Color {
        scheme == .dark ? surfaceDark : surfaceLight
    }

    static func ink(for scheme: ColorScheme) -> Color {
        scheme == .dark ? onDark : ink
    }

    static func muted(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 170 / 255, green: 183 / 255, blue: 192 / 255) : muted
    }

    static func accent(for scheme: ColorScheme) -> Color {
        scheme == .dark ? coralDarkMode : coral
    }

    static func softSurface(for scheme: ColorScheme) -> Color {
        scheme == .dark ? surfaceSoftDark : surfaceSoft
    }

    static func selected(for scheme: ColorScheme) -> Color {
        scheme == .dark ? selectedSurfaceDark : selectedSurface
    }

    static func border(for scheme: ColorScheme) -> Color {
        scheme == .dark ? borderDark : border
    }
}
