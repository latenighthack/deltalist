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
            targets: ["DemoCore"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "DemoCore",
            path: "../../demo-core/build/XCFrameworks/debug/DemoCore.xcframework"
        ),
    ]
)
