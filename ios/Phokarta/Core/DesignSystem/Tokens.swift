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
    // Source-compatible names for the shared blue/mist semantic palette.
    static let coral = Color(red: 37 / 255, green: 99 / 255, blue: 235 / 255)
    static let coralDark = Color(red: 29 / 255, green: 78 / 255, blue: 216 / 255)
    static let ink = Color(red: 23 / 255, green: 32 / 255, blue: 51 / 255)
    static let sand = Color(red: 247 / 255, green: 250 / 255, blue: 1)
    static let sage = Color(red: 15 / 255, green: 107 / 255, blue: 120 / 255)
    static let mist = Color(red: 229 / 255, green: 241 / 255, blue: 248 / 255)
    static let muted = Color(red: 102 / 255, green: 112 / 255, blue: 133 / 255)
    static let surfaceLight = Color.white
    static let backgroundDark = Color(red: 16 / 255, green: 23 / 255, blue: 34 / 255)
    static let surfaceDark = Color(red: 23 / 255, green: 32 / 255, blue: 51 / 255)
    static let onDark = Color(red: 230 / 255, green: 237 / 255, blue: 247 / 255)
    static let coralDarkMode = Color(red: 158 / 255, green: 193 / 255, blue: 1)

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
