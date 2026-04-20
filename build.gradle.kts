plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "FlowPath - AnnoMask"
    group = "io.github.qupath"
    version = "0.2.0"
    description = "Convert TIFF segmentation masks to QuPath detection objects via GeoJSON."
    automaticModule = "qupath.ext.annomask"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testImplementation("org.openjfx:javafx-base:25.0.2")
    testImplementation("org.openjfx:javafx-graphics:25.0.2")
    testImplementation("org.openjfx:javafx-controls:25.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
