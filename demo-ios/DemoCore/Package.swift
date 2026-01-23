// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DemoCore",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(
            name: "DemoCore",
            targets: ["DemoCoreWrapper"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "DemoCore",
            path: "../../demo-core/build/XCFrameworks/debug/DemoCore.xcframework"
        ),
        .binaryTarget(
            name: "DeltaListCore",
            path: "../../deltalist-core/build/XCFrameworks/debug/DeltaListCore.xcframework"
        ),
        // Wrapper target that links both frameworks
        .target(
            name: "DemoCoreWrapper",
            dependencies: ["DemoCore", "DeltaListCore"],
            path: "Sources"
        ),
    ]
)
