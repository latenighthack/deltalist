// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DeltaListUI",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(
            name: "DeltaListUI",
            targets: ["DeltaListUI"]
        ),
    ],
    dependencies: [
        // DeltaListCore will be added as a local package or XCFramework from KMP
    ],
    targets: [
        .target(
            name: "DeltaListUI",
            dependencies: [],
            path: "Sources/DeltaListUI"
        ),
        .testTarget(
            name: "DeltaListUITests",
            dependencies: ["DeltaListUI"]
        ),
    ]
)
