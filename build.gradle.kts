
import org.gradle.kotlin.dsl.support.zipTo
import org.openjfx.gradle.JavaFXOptions

buildscript {
    repositories {
        mavenCentral()
    }
}

group = "com.sgar"
version = "1.0-SNAPSHOT"
val cPlatforms = listOf("mac", "linux", "win") // compile for these platforms. "mac", "linux", "win"
val minJavaVersion = 18
println("Current Java version: ${JavaVersion.current()}")
if (JavaVersion.current().majorVersion.toInt() < minJavaVersion) throw GradleException("Use Java >= $minJavaVersion")

println("Current Java version: ${JavaVersion.current()}")

plugins {
    scala
    id("idea")
    application
    id("com.github.ben-manes.versions") version "0.43.0"
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.runtime") version "1.12.7"
}

application {
    mainClass.set("sgar.Sgar")
    applicationDefaultJvmArgs = listOf("-Dprism.verbose=true", "-Dprism.order=sw") // use software renderer
}

repositories {
    mavenCentral()
}

javafx {
    version = "18"
//    modules = listOf("javafx.base", "javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.media", "javafx.swing")
    modules = listOf("javafx.base", "javafx.controls", "javafx.media")
    // set compileOnly for crosspackage to avoid packaging host javafx jmods for all target platforms
    configuration = if (project.gradle.startParameter.taskNames.intersect(listOf("crosspackage", "dist")).isNotEmpty()) "compileOnly" else "implementation"
}
val javaFXOptions = the<JavaFXOptions>()

dependencies {
    implementation("org.scala-lang:scala-library:2.13.10")
    implementation("org.scalafx:scalafx_2.13:18.0.2-R29")
    implementation("jakarta.mail:jakarta.mail-api:2.0.1")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("com.sun.mail:gimap:2.0.1")
    cPlatforms.forEach {platform ->
        val cfg = configurations.create("javafx_$platform")
        org.openjfx.gradle.JavaFXModule.getJavaFXModules(javaFXOptions.modules).forEach { m ->
            project.dependencies.add(cfg.name,"org.openjfx:${m.artifactName}:${javaFXOptions.version}:$platform")
        }
    }
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    // check "gradle suggestModules", and add jdk.crypto.ec for ssl handshake
    modules.set(listOf("java.desktop", "jdk.unsupported", "jdk.jfr", "java.logging", "java.xml", "java.security.sasl", "java.datatransfer",
            "jdk.crypto.ec"))
    if (cPlatforms.contains("mac")) targetPlatform("mac", System.getenv("JDK_MAC_HOME"))
    if (cPlatforms.contains("win")) targetPlatform("win", System.getenv("JDK_WIN_HOME"))
    if (cPlatforms.contains("linux")) targetPlatform("linux", System.getenv("JDK_LINUX_HOME"))
}

open class CrossPackage : DefaultTask() {
    @Input var execfilename = "execfilename"
    @Input var macicnspath = "macicnspath"

    @TaskAction
    fun crossPackage() {
        File("${project.buildDir.path}/crosspackage/").mkdirs()
        project.runtime.targetPlatforms.get().forEach { (t, _) ->
            println("targetplatform: $t")
            val imgdir = "${project.runtime.imageDir.get()}/${project.name}-$t"
            println("imagedir: $imgdir")
            when(t) {
                "mac" -> {
                    val appp = File(project.buildDir.path + "/crosspackage/mac/$execfilename.app").path
                    project.delete(appp)
                    project.copy {
                        into(appp)
                        from(macicnspath) {
                            into("Contents/Resources").rename { "$execfilename.icns" }
                        }
                        from("$imgdir/${project.application.executableDir}/${project.application.applicationName}") {
                            into("Contents/MacOS")
                        }
                        from(imgdir) {
                            into("Contents")
                        }
                    }
                    val pf = File("$appp/Contents/Info.plist")
                    pf.writeText("""
                        <?xml version="1.0" ?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                         <dict>
                          <key>LSMinimumSystemVersion</key>
                          <string>10.9</string>
                          <key>CFBundleDevelopmentRegion</key>
                          <string>English</string>
                          <key>CFBundleAllowMixedLocalizations</key>
                          <true/>
                          <key>CFBundleExecutable</key>
                          <string>$execfilename</string>
                          <key>CFBundleIconFile</key>
                          <string>$execfilename.icns</string>
                          <key>CFBundleIdentifier</key>
                          <string>${project.group}</string>
                          <key>CFBundleInfoDictionaryVersion</key>
                          <string>6.0</string>
                          <key>CFBundleName</key>
                          <string>${project.name}</string>
                          <key>CFBundlePackageType</key>
                          <string>APPL</string>
                          <key>CFBundleShortVersionString</key>
                          <string>${project.version}</string>
                          <key>CFBundleSignature</key>
                          <string>????</string>
                          <!-- See http://developer.apple.com/library/mac/#releasenotes/General/SubmittingToMacAppStore/_index.html
                               for list of AppStore categories -->
                          <key>LSApplicationCategoryType</key>
                          <string>Unknown</string>
                          <key>CFBundleVersion</key>
                          <string>100</string>
                          <key>NSHumanReadableCopyright</key>
                          <string>Copyright (C) 2019</string>
                          <key>NSHighResolutionCapable</key>
                          <string>true</string>
                         </dict>
                        </plist>
                    """.trimIndent())
                    // touch folder to update Finder
                    File(appp).setLastModified(System.currentTimeMillis())
                    // zip it
                    zipTo(File("${project.buildDir.path}/crosspackage/$execfilename-mac.zip"), File("${project.buildDir.path}/crosspackage/mac"))
                }
                "win" -> {
                    File("$imgdir/bin/$execfilename.bat").delete() // from runtime, not nice
                    val pf = File("$imgdir/$execfilename.bat")
                    pf.writeText("""
                        set JLINK_VM_OPTIONS=${project.application.applicationDefaultJvmArgs.joinToString(" ")}
                        set DIR=%~dp0
                        start "" "%DIR%\bin\javaw" %JLINK_VM_OPTIONS% -classpath "%DIR%/lib/*" ${project.application.mainClass.get()} 
                    """.trimIndent())
                    zipTo(File("${project.buildDir.path}/crosspackage/$execfilename-win.zip"), File(imgdir))
                }
                "linux" -> {
                    zipTo(File("${project.buildDir.path}/crosspackage/$execfilename-linux.zip"), File(imgdir))
                }
            }
        }
    }
}

tasks.register<CrossPackage>("crosspackage") {
    dependsOn("runtime")
    execfilename = "GmailAttachmentRemover"
    macicnspath = "src/deploy/macosx/GmailAttachmentRemover.icns"
}

tasks.withType(CreateStartScripts::class).forEach {script ->
    script.doFirst {
        script.classpath =  files("lib/*")
    }
}

// copy jmods for each platform
tasks["runtime"].doLast {
    cPlatforms.forEach { platform ->
        println("Copy jmods for platform $platform")
        val cfg = configurations["javafx_$platform"]
        cfg.resolvedConfiguration.files.forEach { f ->
            copy {
                from(f)
                into("${project.runtime.imageDir.get()}/${project.name}-$platform/lib")
            }
        }
    }
}

task("dist") {
    dependsOn("crosspackage")
    doLast {
        println("Deleting build/[image,jre,install]")
        project.delete(project.runtime.imageDir.get(), project.runtime.jreDir.get(), "${project.buildDir.path}/install")
        println("Created zips in build/crosspackage")
    }
}
