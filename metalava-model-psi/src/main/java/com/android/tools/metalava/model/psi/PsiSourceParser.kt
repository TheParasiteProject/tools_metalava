/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.model.psi

import com.android.SdkConstants
import com.android.tools.lint.UastEnvironment
import com.android.tools.lint.annotations.Extractor
import com.android.tools.lint.checks.infrastructure.ClassName
import com.android.tools.lint.detector.api.Project
import com.android.tools.metalava.model.AnnotationManager
import com.android.tools.metalava.model.ClassResolver
import com.android.tools.metalava.model.noOpAnnotationManager
import com.android.tools.metalava.model.source.DEFAULT_JAVA_LANGUAGE_LEVEL
import com.android.tools.metalava.model.source.SourceCodebase
import com.android.tools.metalava.model.source.SourceParser
import com.android.tools.metalava.reporter.Issues
import com.android.tools.metalava.reporter.Reporter
import com.intellij.pom.java.LanguageLevel
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl

internal val defaultJavaLanguageLevel = LanguageLevel.parse(DEFAULT_JAVA_LANGUAGE_LEVEL)!!

internal val defaultKotlinLanguageLevel = LanguageVersionSettingsImpl.DEFAULT

fun kotlinLanguageVersionSettings(value: String?): LanguageVersionSettings {
    val languageLevel =
        LanguageVersion.fromVersionString(value)
            ?: throw IllegalStateException(
                "$value is not a valid or supported Kotlin language level"
            )
    val apiVersion = ApiVersion.createByLanguageVersion(languageLevel)
    return LanguageVersionSettingsImpl(languageLevel, apiVersion)
}

/**
 * Parses a set of sources into a [PsiBasedCodebase].
 *
 * The codebases will use a project environment initialized according to the properties passed to
 * the constructor and the paths passed to [parseSources].
 */
internal class PsiSourceParser(
    private val psiEnvironmentManager: PsiEnvironmentManager,
    private val reporter: Reporter,
    private val annotationManager: AnnotationManager = noOpAnnotationManager,
    private val javaLanguageLevel: LanguageLevel = defaultJavaLanguageLevel,
    private val kotlinLanguageLevel: LanguageVersionSettings = defaultKotlinLanguageLevel,
    private val useK2Uast: Boolean = false,
    private val jdkHome: File? = null,
) : SourceParser {

    override fun getClassResolver(classPath: List<File>): ClassResolver {
        val uastEnvironment = loadUastFromJars(classPath)
        return PsiBasedClassResolver(uastEnvironment, annotationManager, reporter)
    }

    /**
     * Returns a codebase initialized from the given Java or Kotlin source files, with the given
     * description.
     *
     * All supplied [File] objects will be mapped to [File.getAbsoluteFile].
     */
    override fun parseSources(
        sources: List<File>,
        description: String,
        sourcePath: List<File>,
        classPath: List<File>,
    ): PsiBasedCodebase {
        val absoluteSources = sources.map { it.absoluteFile }

        val absoluteSourceRoots =
            sourcePath.filter { it.path.isNotBlank() }.map { it.absoluteFile }.toMutableList()

        // Add in source roots implied by the source files
        extractRoots(reporter, absoluteSources, absoluteSourceRoots)

        val absoluteClasspath = classPath.map { it.absoluteFile }

        return parseAbsoluteSources(
            absoluteSources,
            description,
            absoluteSourceRoots,
            absoluteClasspath,
        )
    }

    /** Returns a codebase initialized from the given set of absolute files. */
    private fun parseAbsoluteSources(
        sources: List<File>,
        description: String,
        sourceRoots: List<File>,
        classpath: List<File>,
    ): PsiBasedCodebase {
        val config = UastEnvironment.Configuration.create(useFirUast = useK2Uast)
        config.javaLanguageLevel = javaLanguageLevel

        val rootDir = sourceRoots.firstOrNull() ?: File("").canonicalFile

        configureUastEnvironment(config, sourceRoots, classpath, rootDir)
        // K1 UAST: loading of JDK (via compiler config, i.e., only for FE1.0), when using JDK9+
        jdkHome?.let {
            if (isJdkModular(it)) {
                config.kotlinCompilerConfig.put(JVMConfigurationKeys.JDK_HOME, it)
                config.kotlinCompilerConfig.put(JVMConfigurationKeys.NO_JDK, false)
            }
        }

        val environment = psiEnvironmentManager.createEnvironment(config)

        val kotlinFiles = sources.filter { it.path.endsWith(SdkConstants.DOT_KT) }
        environment.analyzeFiles(kotlinFiles)

        val units = Extractor.createUnitsForFiles(environment.ideaProject, sources)
        val packageDocs = gatherPackageJavadoc(sources, sourceRoots)

        val codebase = PsiBasedCodebase(rootDir, description, annotationManager, reporter)
        codebase.initialize(environment, units, packageDocs)
        return codebase
    }

    private fun isJdkModular(homePath: File): Boolean {
        return File(homePath, "jmods").isDirectory
    }

    override fun loadFromJar(apiJar: File, preFiltered: Boolean): SourceCodebase {
        val environment = loadUastFromJars(listOf(apiJar))
        val codebase =
            PsiBasedCodebase(apiJar, "Codebase loaded from $apiJar", annotationManager, reporter)
        codebase.initialize(environment, apiJar, preFiltered)
        return codebase
    }

    /** Initializes a UAST environment using the [apiJars] as classpath roots. */
    private fun loadUastFromJars(apiJars: List<File>): UastEnvironment {
        val config = UastEnvironment.Configuration.create(useFirUast = useK2Uast)
        // Use the empty dir otherwise this will end up scanning the current working directory.
        configureUastEnvironment(config, listOf(psiEnvironmentManager.emptyDir), apiJars)

        val environment = psiEnvironmentManager.createEnvironment(config)
        environment.analyzeFiles(emptyList()) // Initializes PSI machinery.
        return environment
    }

    private fun configureUastEnvironment(
        config: UastEnvironment.Configuration,
        sourceRoots: List<File>,
        classpath: List<File>,
        rootDir: File = sourceRoots.firstOrNull() ?: File("").canonicalFile
    ) {
        // TODO(jsjeon): should set language version _per_ module (Lint Project)
        val lintClient = MetalavaCliClient(kotlinLanguageLevel)
        // From ...lint.detector.api.Project, `dir` is, e.g., /tmp/foo/dev/src/project1,
        // and `referenceDir` is /tmp/foo/. However, in many use cases, they are just same.
        // `referenceDir` is used to adjust `lib` dir accordingly if needed,
        // but we set `classpath` anyway below.
        val lintProject =
            Project.create(lintClient, /* dir = */ rootDir, /* referenceDir = */ rootDir)
        lintProject.javaSourceFolders.addAll(sourceRoots)
        lintProject.javaLibraries.addAll(classpath)
        config.addModules(
            listOf(
                UastEnvironment.Module(
                    lintProject,
                    // K2 UAST: building KtSdkModule for JDK
                    jdkHome,
                    includeTests = false,
                    includeTestFixtureSources = false,
                    isUnitTest = false
                )
            ),
        )
    }
}

private fun gatherPackageJavadoc(sources: List<File>, sourceRoots: List<File>): PackageDocs {
    val packageComments = HashMap<String, String>(100)
    val overviewHtml = HashMap<String, String>(10)
    val hiddenPackages = HashSet<String>(100)
    val sortedSourceRoots = sourceRoots.sortedBy { -it.name.length }
    for (file in sources) {
        var javadoc = false
        val map =
            when (file.name) {
                PACKAGE_HTML -> {
                    javadoc = true
                    packageComments
                }
                OVERVIEW_HTML -> {
                    overviewHtml
                }
                else -> continue
            }
        var contents = file.readText(Charsets.UTF_8)
        if (javadoc) {
            contents = packageHtmlToJavadoc(contents)
        }

        // Figure out the package: if there is a java file in the same directory, get the package
        // name from the java file. Otherwise, guess from the directory path + source roots.
        // NOTE: This causes metalava to read files other than the ones explicitly passed to it.
        var pkg =
            file.parentFile
                ?.listFiles()
                ?.filter { it.name.endsWith(SdkConstants.DOT_JAVA) }
                ?.asSequence()
                ?.mapNotNull { findPackage(it) }
                ?.firstOrNull()
        if (pkg == null) {
            // Strip the longest prefix source root.
            val prefix = sortedSourceRoots.firstOrNull { file.startsWith(it) }?.path ?: ""
            pkg = file.parentFile.path.substring(prefix.length).trim('/').replace("/", ".")
        }
        map[pkg] = contents
        if (contents.contains("@hide")) {
            hiddenPackages.add(pkg)
        }
    }

    return PackageDocs(packageComments, overviewHtml, hiddenPackages)
}

const val PACKAGE_HTML = "package.html"
const val OVERVIEW_HTML = "overview.html"

private fun skippableDirectory(file: File): Boolean =
    file.path.endsWith(".git") && file.name == ".git"

private fun addSourceFiles(reporter: Reporter, list: MutableList<File>, file: File) {
    if (file.isDirectory) {
        if (skippableDirectory(file)) {
            return
        }
        if (Files.isSymbolicLink(file.toPath())) {
            reporter.report(
                Issues.IGNORING_SYMLINK,
                file,
                "Ignoring symlink during source file discovery directory traversal"
            )
            return
        }
        val files = file.listFiles()
        if (files != null) {
            for (child in files) {
                addSourceFiles(reporter, list, child)
            }
        }
    } else if (file.isFile) {
        when {
            file.name.endsWith(SdkConstants.DOT_JAVA) ||
                file.name.endsWith(SdkConstants.DOT_KT) ||
                file.name.equals(PACKAGE_HTML) ||
                file.name.equals(OVERVIEW_HTML) -> list.add(file)
        }
    }
}

fun gatherSources(reporter: Reporter, sourcePath: List<File>): List<File> {
    val sources = mutableListOf<File>()
    for (file in sourcePath) {
        if (file.path.isBlank()) {
            // --source-path "" means don't search source path; use "." for pwd
            continue
        }
        addSourceFiles(reporter, sources, file.absoluteFile)
    }
    return sources.sortedWith(compareBy { it.name })
}

fun extractRoots(
    reporter: Reporter,
    sources: List<File>,
    sourceRoots: MutableList<File> = mutableListOf()
): List<File> {
    // Cache for each directory since computing root for a source file is
    // expensive
    val dirToRootCache = mutableMapOf<String, File>()
    for (file in sources) {
        val parent = file.parentFile ?: continue
        val found = dirToRootCache[parent.path]
        if (found != null) {
            continue
        }

        val root = findRoot(reporter, file) ?: continue
        dirToRootCache[parent.path] = root

        if (!sourceRoots.contains(root)) {
            sourceRoots.add(root)
        }
    }

    return sourceRoots
}

/**
 * If given a full path to a Java or Kotlin source file, produces the path to the source root if
 * possible.
 */
private fun findRoot(reporter: Reporter, file: File): File? {
    val path = file.path
    if (path.endsWith(SdkConstants.DOT_JAVA) || path.endsWith(SdkConstants.DOT_KT)) {
        val pkg = findPackage(file) ?: return null
        val parent = file.parentFile ?: return null
        val endIndex = parent.path.length - pkg.length
        val before = path[endIndex - 1]
        if (before == '/' || before == '\\') {
            return File(path.substring(0, endIndex))
        } else {
            reporter.report(
                Issues.IO_ERROR,
                file,
                "Unable to determine the package name. " +
                    "This usually means that a source file was where the directory does not seem to match the package " +
                    "declaration; we expected the path $path to end with /${pkg.replace('.', '/') + '/' + file.name}"
            )
        }
    }

    return null
}

/** Finds the package of the given Java/Kotlin source file, if possible */
fun findPackage(file: File): String? {
    val source = file.readText(Charsets.UTF_8)
    return findPackage(source)
}

/** Finds the package of the given Java/Kotlin source code, if possible */
fun findPackage(source: String): String? {
    return ClassName(source).packageName
}
