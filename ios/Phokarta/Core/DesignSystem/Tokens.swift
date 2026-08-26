import SwiftUI

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
    // Brand reference: Android ui/theme/Theme.kt
    static let coral = Color(red: 232 / 255, green: 111 / 255, blue: 81 / 255)
    static let coralDark = Color(red: 185 / 255, green: 67 / 255, blue: 46 / 255)
    static let ink = Color(red: 32 / 255, green: 35 / 255, blue: 31 / 255)
    static let sand = Color(red: 1, green: 248 / 255, blue: 241 / 255)
    static let sage = Color(red: 66 / 255, green: 107 / 255, blue: 91 / 255)
    static let mist = Color(red: 233 / 255, green: 240 / 255, blue: 236 / 255)
    static let muted = Color(red: 114 / 255, green: 119 / 255, blue: 112 / 255)
    static let surfaceLight = Color.white
    static let backgroundDark = Color(red: 24 / 255, green: 26 / 255, blue: 24 / 255)
    static let surfaceDark = Color(red: 32 / 255, green: 35 / 255, blue: 31 / 255)
    static let onDark = Color(red: 228 / 255, green: 227 / 255, blue: 223 / 255)
    static let coralDarkMode = Color(red: 1, green: 181 / 255, blue: 159 / 255)

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
        scheme == .dark ? Color(red: 196 / 255, green: 200 / 255, blue: 194 / 255) : muted
    }

    static func accent(for scheme: ColorScheme) -> Color {
        scheme == .dark ? coralDarkMode : coral
    }
}
