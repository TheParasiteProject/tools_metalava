import com.android.tools.metalava.CREATE_ARCHIVE_TASK
import com.android.tools.metalava.buildinfo.CREATE_BUILD_INFO_TASK

defaultTasks =
    mutableListOf(
        "installDist",
        "test",
        CREATE_ARCHIVE_TASK,
        CREATE_BUILD_INFO_TASK,
        "lint",
        "ktCheck",
    )

plugins {
    id("application")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("metalava-build-plugin")
}

application {
    mainClass.set("com.android.tools.metalava.Driver")
    applicationDefaultJvmArgs = listOf("-ea", "-Xms2g", "-Xmx4g")
}

dependencies {
    implementation(libs.androidToolsExternalUast)
    implementation(libs.androidToolsExternalKotlinCompiler)
    implementation(libs.androidToolsExternalIntellijCore)
    implementation(libs.androidLintApi)
    implementation(libs.androidLintChecks)
    implementation(libs.androidLintGradle)
    implementation(libs.androidLint)
    implementation(libs.androidToolsCommon)
    implementation(libs.androidToolsSdkCommon)
    implementation(libs.androidToolsSdklib)
    implementation(libs.clikt)
    implementation(libs.kotlinStdlib)
    implementation(libs.kotlinReflect)
    implementation(libs.asm)
    implementation(libs.asmTree)
    implementation(libs.gson)
    testImplementation(libs.androidLintTests)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinTest)
}

/** The location into which a fake representation of the prebuilts/sdk directory will be written. */
val testPrebuiltsSdkDir = layout.buildDirectory.dir("prebuilts/sdk")

/**
 * Register tasks to emulate parts of the prebuilts/sdk repository using source from this directory.
 *
 * [sourceDir] is the path to the root directory of code that is compiled into a jar that
 * corresponds to the jar specified by [destJar].
 *
 * [destJar] is the path to a jar within prebuilts/sdk. The fake jar created from [sourceDir] is
 * copied to getBuildDirectory()/prebuilts/sdk/$destJar.
 *
 * The jars created by this can be accessed by tests via the `METALAVA_TEST_PREBUILTS_SDK_ROOT`
 * environment variable which is set to point to getBuildDirectory()/prebuilts/sdk; see [testTask].
 */
fun registerTestPrebuiltsSdkTasks(sourceDir: String, destJar: String): TaskProvider<Jar> {
    val basename = sourceDir.replace("/", "-")
    val javaCompileTaskName = "$basename.classes"
    val jarTaskName = "$basename.jar"

    val compileTask =
        project.tasks.register(javaCompileTaskName, JavaCompile::class) {
            options.compilerArgs = listOf("--patch-module", "java.base=" + file(sourceDir))
            source = fileTree(sourceDir)
            classpath = project.files()
            destinationDirectory.set(layout.buildDirectory.dir(javaCompileTaskName))
        }

    val destJarFile = File(destJar)
    val dir = destJarFile.parent
    val filename = destJarFile.name
    if (dir == ".") {
        throw IllegalArgumentException("bad destJar argument '$destJar'")
    }

    val jarTask =
        project.tasks.register(jarTaskName, Jar::class) {
            from(compileTask.flatMap { it.destinationDirectory })
            archiveFileName.set(filename)
            destinationDirectory.set(testPrebuiltsSdkDir.map { it.dir(dir) })
        }

    return jarTask
}

val testPrebuiltsSdkApi30 =
    registerTestPrebuiltsSdkTasks("src/testdata/prebuilts-sdk-test/30", "30/public/android.jar")
val testPrebuiltsSdkApi31 =
    registerTestPrebuiltsSdkTasks("src/testdata/prebuilts-sdk-test/31", "31/public/android.jar")
val testPrebuiltsSdkExt1 =
    registerTestPrebuiltsSdkTasks(
        "src/testdata/prebuilts-sdk-test/extensions/1",
        "extensions/1/public/framework-ext.jar"
    )
val testPrebuiltsSdkExt2 =
    registerTestPrebuiltsSdkTasks(
        "src/testdata/prebuilts-sdk-test/extensions/2",
        "extensions/2/public/framework-ext.jar"
    )
val testPrebuiltsSdkExt3 =
    registerTestPrebuiltsSdkTasks(
        "src/testdata/prebuilts-sdk-test/extensions/3",
        "extensions/3/public/framework-ext.jar"
    )

project.tasks.register("test-sdk-extensions-info.xml", Copy::class) {
    from("src/testdata/prebuilts-sdk-test/sdk-extensions-info.xml")
    into(testPrebuiltsSdkDir)
}

project.tasks.register("test-prebuilts-sdk") {
    dependsOn(testPrebuiltsSdkApi30)
    dependsOn(testPrebuiltsSdkApi31)
    dependsOn(testPrebuiltsSdkExt1)
    dependsOn(testPrebuiltsSdkExt2)
    dependsOn(testPrebuiltsSdkExt3)
    dependsOn("test-sdk-extensions-info.xml")
}

tasks.named<Test>("test").configure {
    dependsOn("test-prebuilts-sdk")
    setEnvironment(
        "METALAVA_TEST_PREBUILTS_SDK_ROOT" to testPrebuiltsSdkDir.get().asFile.absolutePath
    )
}
