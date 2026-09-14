import Foundation
import XCTest

final class ReleasePackagingContractTests: XCTestCase {
    private var iosRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    func testReleaseConfigurationUsesBetaAPIAndAlignedVersion() throws {
        let release = try sourceText("Config/Release.xcconfig")
        XCTAssertTrue(release.contains("PHOKARTA_API_BASE_URL = https:/$()/api.phokarta.com/"))
        XCTAssertFalse(release.contains("api.phokarta.invalid"))
        XCTAssertFalse(release.contains("127.0.0.1"))

        let projectDefinition = try sourceText("project.yml")
        XCTAssertTrue(projectDefinition.contains("MARKETING_VERSION: \"0.9.0\""))
        XCTAssertTrue(projectDefinition.contains("CURRENT_PROJECT_VERSION: \"1\""))

        let generatedProject = try sourceText("Phokarta.xcodeproj/project.pbxproj")
        XCTAssertEqual(generatedProject.components(separatedBy: "MARKETING_VERSION = 0.9.0;").count - 1, 2)
        XCTAssertEqual(generatedProject.components(separatedBy: "CURRENT_PROJECT_VERSION = 1;").count - 1, 2)
    }

    func testPrivacyManifestMatchesActualRequiredReasonAPIUse() throws {
        let info = try propertyList("Phokarta/Resources/Info.plist")
        XCTAssertEqual(info["ITSAppUsesNonExemptEncryption"] as? Bool, false)

        let manifest = try propertyList("Phokarta/Resources/PrivacyInfo.xcprivacy")
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual(manifest["NSPrivacyTrackingDomains"] as? [String], [])
        let collectedTypes = try XCTUnwrap(
            manifest["NSPrivacyCollectedDataTypes"] as? [[String: Any]]
        )
        XCTAssertEqual(collectedTypes.count, 6)
        XCTAssertEqual(
            Set(collectedTypes.compactMap { $0["NSPrivacyCollectedDataType"] as? String }),
            Set([
                "NSPrivacyCollectedDataTypeName",
                "NSPrivacyCollectedDataTypeEmailAddress",
                "NSPrivacyCollectedDataTypeUserID",
                "NSPrivacyCollectedDataTypePhotosorVideos",
                "NSPrivacyCollectedDataTypeOtherUserContent",
                "NSPrivacyCollectedDataTypeProductInteraction"
            ])
        )
        for collectedType in collectedTypes {
            XCTAssertEqual(collectedType["NSPrivacyCollectedDataTypeLinked"] as? Bool, true)
            XCTAssertEqual(collectedType["NSPrivacyCollectedDataTypeTracking"] as? Bool, false)
            XCTAssertEqual(
                collectedType["NSPrivacyCollectedDataTypePurposes"] as? [String],
                ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]
            )
        }

        let accessedTypes = try XCTUnwrap(
            manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]]
        )
        XCTAssertEqual(accessedTypes.count, 1)
        XCTAssertEqual(
            accessedTypes[0]["NSPrivacyAccessedAPIType"] as? String,
            "NSPrivacyAccessedAPICategoryFileTimestamp"
        )
        XCTAssertEqual(
            accessedTypes[0]["NSPrivacyAccessedAPITypeReasons"] as? [String],
            ["C617.1"]
        )
    }

    func testAppStoreIconIsCompleteOpaquePNG() throws {
        let contentsData = try sourceData(
            "Phokarta/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json"
        )
        let contents = try XCTUnwrap(
            JSONSerialization.jsonObject(with: contentsData) as? [String: Any]
        )
        let images = try XCTUnwrap(contents["images"] as? [[String: Any]])
        let universal = try XCTUnwrap(images.first {
            $0["idiom"] as? String == "universal" && $0["platform"] as? String == "ios"
        })
        let filename = try XCTUnwrap(universal["filename"] as? String)
        XCTAssertEqual(universal["size"] as? String, "1024x1024")

        let data = try sourceData(
            "Phokarta/Resources/Assets.xcassets/AppIcon.appiconset/\(filename)"
        )
        XCTAssertGreaterThan(data.count, 25)
        XCTAssertEqual(Array(data.prefix(8)), [137, 80, 78, 71, 13, 10, 26, 10])
        XCTAssertEqual(pngUInt32(data, offset: 16), 1_024)
        XCTAssertEqual(pngUInt32(data, offset: 20), 1_024)
        XCTAssertEqual(data[25], 2, "App Store icon must be opaque RGB without alpha")
    }

    private func sourceText(_ path: String) throws -> String {
        String(decoding: try sourceData(path), as: UTF8.self)
    }

    private func sourceData(_ path: String) throws -> Data {
        try Data(contentsOf: iosRoot.appendingPathComponent(path))
    }

    private func propertyList(_ path: String) throws -> [String: Any] {
        let object = try PropertyListSerialization.propertyList(
            from: sourceData(path),
            options: [],
            format: nil
        )
        return try XCTUnwrap(object as? [String: Any])
    }

    private func pngUInt32(_ data: Data, offset: Int) -> UInt32 {
        data[offset..<(offset + 4)].reduce(UInt32.zero) { value, byte in
            (value << 8) | UInt32(byte)
        }
    }
}
