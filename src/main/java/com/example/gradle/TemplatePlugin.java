package com.example.gradle;

import groovy.json.JsonOutput;
import groovy.util.Node;
import groovy.util.NodeList;
import net.minecraftforge.gradle.tasks.user.reobf.ReobfTask;
import net.minecraftforge.gradle.user.UserExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TemplatePlugin - the project's own buildSrc plugin.
 *
 * Implements everything the stock gradle scripts used to do, so the root
 * build.gradle can stay minimal (GTNH-style). Apply it AFTER the 'forge'
 * plugin: {@code apply plugin: 'com.example.gradle.template'}.
 *
 * Provided features (each gated by its gradle.properties switch):
 *   * minecraft extension defaults (version/runDir) + MCLib repackage SRG
 *   * repositories and mod dependencies (Kotlin/Scala/UniMixins/MCLib)
 *   * two-mode versioning (Tags.java extraction / GTNH-style git tags)
 *   * processResources: mcmod.info expansion + auto-generated Mixin config
 *   * UniMixins integration (manifest, refmap, reobf SRG, dev tweakClass)
 *   * developer account for runClient
 *   * compile settings (UTF-8, lint, Kotlin JVM target)
 *   * deobfJar / sourcesJar / updateGradleProperties / LICENSE packaging
 *   * maven-publish with POM dependency filtering
 */
public class TemplatePlugin implements Plugin<Project> {

    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;

        boolean kotlin = boolProp("enableUsingKotlin");
        boolean scala = boolProp("enableUsingScala");
        boolean mclib = boolProp("enableUsingMCLib");
        boolean mixin = boolProp("enableUsingMixin");
        boolean beneath = boolProp("enableBeneathMixin");

        checkBeneathMixinMode(beneath, mixin);
        project.getExtensions().getExtraProperties().set("beneathMixinEnabled", beneath);

        configureMinecraft(mclib);
        configureRepositories();
        configureConfigurations(mclib);
        configureDependencies(kotlin, scala, mixin, beneath, mclib);
        bundleMclibIntoJar(mclib);
        Versioning.apply(project);
        configureProcessResources(beneath, mixin);
        configureMixinIntegration(mixin, beneath);
        configureDevAccount();
        configureCompileSettings(kotlin);
        registerJars();
        configureLicensePackaging();
        configurePublishing();
    }

    // === === === helpers === === ===

    private boolean boolProp(String name) {
        Object value = project.findProperty(name);
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String strProp(String name, String def) {
        Object value = project.findProperty(name);
        return value == null || value.toString().isEmpty() ? def : value.toString();
    }

    private String strProp(String name) {
        return strProp(name, "");
    }

    private String modGroupPath() {
        return strProp("modGroupId").replace('.', '/');
    }

    // === === === Mixin Mode Resolution === === ===
    // Two concepts are kept separate:
    //   1. Mixin runtime  : UniMixins on the classpath + MixinTweaker in the dev
    //                       environment, so mods that USE Mixin can load.
    //   2. Own Mixin config: this mod owning mixins.<modId>.json and declaring it
    //                       via the jar manifest (MixinConfigs).
    // enableBeneathMixin=true means "(2) does not apply to this mod, but (1) must
    // stay present for a prerequisite mod". It therefore only makes sense
    // together with enableUsingMixin=true.
    private void checkBeneathMixinMode(boolean beneath, boolean mixin) {
        if (beneath && !mixin) {
            throw new GradleException("enableBeneathMixin=true requires enableUsingMixin=true: " +
                    "Beneath Mixin mode keeps the Mixin runtime for prerequisite mods, " +
                    "so the Mixin integration cannot be disabled entirely.");
        }
    }

    // === === === minecraft extension === === ===

    private void configureMinecraft(boolean mclib) {
        UserExtension mc = project.getExtensions().getByType(UserExtension.class);
        mc.setVersion("1.7.10-10.13.4.1614-1.7.10");
        mc.setRunDir("minecraft");

        // MCLib repackage: at reobf time every reference to (and definition of)
        // makamys/mclib is rewritten into <modPackage>/repackage/makamys/mclib.
        // In the dev environment (runClient) no reobf happens and the classes
        // stay at makamys.mclib, which is what the compat sources import.
        if (mclib) {
            mc.srgExtra("PK: makamys/mclib " + modGroupPath() + "/repackage/makamys/mclib");
        }
    }

    // === === === repositories === === ===

    private void configureRepositories() {
        project.getRepositories().mavenLocal();
        project.getRepositories().mavenCentral();
        project.getRepositories().flatDir(repo -> repo.dirs("libs"));
        project.getRepositories().maven(repo -> {
            repo.setName("Curse Maven");
            repo.setUrl("https://cursemaven.com");
        });
        project.getRepositories().maven(repo -> {
            repo.setName("Modrinth Maven");
            repo.setUrl("https://api.modrinth.com/maven");
        });
        project.getRepositories().maven(repo -> {
            repo.setName("Aliyun");
            repo.setUrl("https://maven.aliyun.com/repository/public/");
        });
        // Jitpack pre-configured: com.github.* dependencies (incl. UniMixins)
        // no longer need the repository to be declared manually
        project.getRepositories().maven(repo -> {
            repo.setName("Jitpack");
            repo.setUrl("https://jitpack.io");
        });
    }

    // === === === configurations === === ===

    private void configureConfigurations(boolean mclib) {
        Configuration includeCompile = project.getConfigurations().create("includeCompile");
        project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(includeCompile);

        if (mclib) {
            // The MCLib README uses "compile.extendsFrom shade" (pre-Gradle-7 idiom).
            // On Gradle 7 the compile classpath is fed by 'implementation' instead,
            // so shade extends that configuration: the shaded library then lands on
            // both the compile and the runtime classpath.
            Configuration shade = project.getConfigurations().create("shade");
            project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(shade);
        } else {
            // MCLib disabled: the compat sources under <modPackage>/mclib/ are
            // excluded from compilation so the build works without the library.
            SourceSet main = sourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            main.getJava().exclude(modGroupPath() + "/mclib/**");
        }
    }

    private SourceSetContainer sourceSets() {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    // === === === dependencies === === ===

    private void configureDependencies(boolean kotlin, boolean scala, boolean mixin, boolean beneath, boolean mclib) {
        DependencyHandler deps = project.getDependencies();

        if (kotlin) {
            // Kotlin stdlib: needed to compile & run in the dev environment.
            // It is NOT bundled into the mod jar - Forgelin's DepLoader downloads
            // the matching Kotlin version at runtime instead.
            deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                    "org.jetbrains.kotlin:kotlin-stdlib:" + project.property("kotlin_version"));
            // Forgelin 2 (LegacyModdingMC): Kotlin language adapter for 1.7.10
            // https://github.com/LegacyModdingMC/Forgelin - resolved from the
            // Modrinth Maven
            deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                    "maven.modrinth:forgelin-legacy:" + project.property("forgelin_version"));
        }

        if (scala) {
            // When Scala is enabled, declare the scala-library yourself (example below)
            deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.scala-lang:scala-library:2.13.16");
        }

        // Local jars under the lib/ directory (gitignored)
        deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, project.fileTree("lib").include("*.jar"));

        // UniMixins: unimixins-all includes UniMix (Mixin fork), SpongeMixins,
        // MixinBooterLegacy, MixinExtras, GTNHMixins, Compat, Mixingasm.
        // Resolved from the jitpack.io repository declared above.
        if (mixin) {
            String unimixinsVersion = project.findProperty("unimixinsVersion").toString();
            String unimixinsDep = "com.github.LegacyModdingMC.UniMixins:unimixins-all-1.7.10:" + unimixinsVersion + ":dev";
            deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, unimixinsDep);
            if (!beneath) {
                // Annotation processor only needed to generate refmaps for this
                // mod's own Mixin classes; Beneath Mixin mode has none.
                deps.add("annotationProcessor", unimixinsDep);
            }
        }

        if (mclib) {
            String mclibVersion = project.findProperty("mclibVersion").toString();
            // codechicken (CodeChickenLib) is only needed for MCLib's
            // InventoryUtils2 helper; exclude it as recommended by MCLib's README.
            ExternalModuleDependency mclibDep = (ExternalModuleDependency)
                    deps.create("com.github.makamys:MCLib:" + mclibVersion);
            mclibDep.exclude(Collections.singletonMap("group", "codechicken"));
            deps.add("shade", mclibDep);
        }
    }

    // Bundle MCLib into the mod jar (minus META-INF), i.e. classic FG 1.2 shading
    private void bundleMclibIntoJar(boolean mclib) {
        if (!mclib) {
            return;
        }
        Configuration shade = project.getConfigurations().getByName("shade");
        project.getTasks().named("jar", Jar.class, jar -> {
            for (File dep : shade.getFiles()) {
                jar.from(project.zipTree(dep), spec -> spec.exclude("META-INF", "META-INF/**"));
            }
        });
        System.out.println(">>> MCLib enabled: shading com.github.makamys:MCLib:" +
                project.findProperty("mclibVersion") + " into " + modGroupPath() + "/repackage/makamys/mclib");
    }

    // === === === processResources === === ===

    // Parse the modDependencies property (FML sorting-rule style, ';'-separated,
    // e.g. "required-after:modid@[1.0,);after:modid2") into a list of plain
    // "modid" / "modid@range" references - the only format the FML mcmod.info
    // parser accepts (see FML VersionParser.parseVersionReference: one entry must
    // split on '@' into at most two parts). Injecting the raw string instead
    // yields a single invalid element like ["required-after:a@[1.0,);after:b"]
    // that makes FML reject the whole mcmod.info file.
    private String dependenciesJson() {
        List<String> list = new ArrayList<>();
        String modDeps = strProp("modDependencies");
        if (!modDeps.isEmpty()) {
            for (String entry : modDeps.split(";")) {
                // Strip the FML sorting-rule prefixes; mcmod.info dependencies
                // carry no required/after semantics of their own.
                String cleaned = entry.trim().replaceFirst("(?i)^(required-after|required-before|after|before):", "");
                if (!cleaned.isEmpty()) {
                    list.add(cleaned);
                }
            }
        }
        return JsonOutput.toJson(list);
    }

    // Same quoting pitfall for authorList: the stock gradle.properties happens to
    // carry the quotes inside the value (authorList="ExampleDude"), which only
    // accidentally produces valid JSON. Strip any wrapping quotes and re-serialize
    // so mcmod.info stays valid regardless of how the property is written.
    private String authorListJson() {
        return JsonOutput.toJson(Collections.singletonList(strProp("authorList").replaceAll("^\"+|\"+$", "")));
    }

    private void configureProcessResources(boolean beneath, boolean mixin) {
        String modId = strProp("modId");
        Map<String, Object> props = new HashMap<>();
        props.put("modversion", project.getVersion().toString());
        props.put("mcversion", project.getExtensions().getByType(UserExtension.class).getVersion());
        props.put("moddescription", strProp("modDescription"));
        props.put("modname", strProp("modName"));
        props.put("modid", modId);
        props.put("url", strProp("url"));
        props.put("authorList", authorListJson());
        props.put("credits", strProp("credits"));
        props.put("logo", strProp("logo"));
        props.put("dependencies", dependenciesJson());

        project.getTasks().named("processResources", Copy.class, task -> {
            props.forEach(task.getInputs()::property);

            task.filesMatching("mcmod.info", spec -> spec.expand(props));

            // Auto-fill the ${modid} placeholder inside the Mixin config (e.g. the
            // refmap name) and rename the template file to mixins.<modId>.json, so
            // it matches the MixinConfigs manifest attribute declared below.
            // Skipped in Beneath Mixin mode: this mod owns no Mixin config there,
            // and a leftover template config must not be expanded/renamed into the
            // jar. The two patterns are OR-ed: the stock template name and the
            // already-renamed name, so each file is processed exactly once.
            if (!beneath) {
                String expectedName = "mixins." + modId + ".json";
                task.filesMatching(Arrays.asList("mixins.examplemod.json", expectedName), details -> {
                    if (!details.getName().equals(expectedName)) {
                        details.setName(expectedName);
                    }
                });
                task.expand(Collections.singletonMap("modid", modId));

                // GTNH-style fallback (GTNHGradle GenerateMixinAssetsTask): when this
                // mod ships NO base Mixin config at all - e.g. it only uses the
                // UniMixins special names mixins.<modId>.early.json /
                // mixins.<modId>.late.json - the jar manifest still declares
                // MixinConfigs=mixins.<modId>.json, and the Mixin runtime would fail
                // at launch looking for a missing config. Generate an empty default
                // config so the manifest entry always resolves and no hand-written
                // mixins.<modId>.json is required. Only relevant when this mod owns
                // a Mixin config (enableUsingMixin=true, non-Beneath mode).
                if (mixin) {
                    File templateConfig = project.file("src/main/resources/mixins.examplemod.json");
                    File baseConfig = project.file("src/main/resources/mixins." + modId + ".json");
                    task.doFirst(t -> {
                        if (!templateConfig.exists() && !baseConfig.exists()) {
                            String mixinPackage = project.hasProperty("mixinsPackage") ? project.property("mixinsPackage").toString() : "mixin";
                            String mixinPlugin = project.hasProperty("mixinPlugin") ? project.property("mixinPlugin").toString() : "";
                            StringBuilder config = new StringBuilder();
                            config.append("{\n");
                            config.append("  \"required\": true,\n");
                            config.append("  \"minVersion\": \"0.8.5-GTNH\",\n");
                            config.append("  \"package\": \"").append(strProp("modGroupId")).append('.').append(mixinPackage).append("\",\n");
                            if (!mixinPlugin.trim().isEmpty()) {
                                config.append("  \"plugin\": \"").append(strProp("modGroupId")).append('.').append(mixinPlugin).append("\",\n");
                            }
                            config.append("  \"refmap\": \"mixins.").append(modId).append(".refmap.json\",\n");
                            config.append("  \"target\": \"@env(DEFAULT)\",\n");
                            config.append("  \"compatibilityLevel\": \"JAVA_8\",\n");
                            config.append("  \"mixins\": [],\n");
                            config.append("  \"client\": [],\n");
                            config.append("  \"server\": []\n");
                            config.append("}\n");
                            File outFile = new File(sourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME).getOutput().getResourcesDir(),
                                    "mixins." + modId + ".json");
                            outFile.getParentFile().mkdirs();
                            writeFile(outFile, config.toString());
                            System.out.println(">>> No base Mixin config found - generated an empty mixins." + modId + ".json for the jar manifest");
                        }
                    });
                }
            } else {
                // Beneath Mixin mode: this mod ships no Mixin config at all. Drop
                // any leftover (template) config from the packaged resources so
                // neither MixinConfigs nor Mixin auto-discovery can ever pick one up.
                task.exclude("mixins.*.json");
            }
        });
    }

    // === === === UniMixins Integration (optional, default ON) === === ===
    // UniMixins provides both Mixin loading and ASM compatibility (Mixingasm
    // module), so no separate MixinBooter / SpongeMixins / ASM compatibility
    // layer is needed. https://github.com/LegacyModdingMC/UniMixins
    //
    // Two distinct modes are supported:
    //   * Normal Mixin mode (beneathMode == false): this mod owns its Mixin
    //     config (mixins.<modId>.json), so the full integration is applied:
    //     MixinConfigs/TweakClass manifest attributes, refmap generation via the
    //     annotation processor, reobf extra SRG.
    //   * Beneath Mixin mode (beneathMode == true): this mod does NOT own any
    //     Mixin config, but the Mixin runtime must still exist so a prerequisite
    //     mod can load its own Mixins. Only the runtime is provided; no
    //     MixinConfigs manifest entry and no refmap are produced.
    private void configureMixinIntegration(boolean mixin, boolean beneath) {
        if (!mixin) {
            // No Mixin at all: still bundle the includeCompile configuration
            // (e.g. a shaded local library) into the jar, as before.
            Configuration includeCompile = project.getConfigurations().getByName("includeCompile");
            project.getTasks().named("jar", Jar.class, jar ->
                    jar.from(project.provider(() -> includeCompile.getFiles().stream()
                            .map(f -> f.isDirectory() ? f : project.zipTree(f))
                            .collect(Collectors.toList()))));
            return;
        }

        // Launch Mixin in the dev environment. Kept in BOTH modes: in Beneath
        // Mixin mode this is exactly what lets the prerequisite mod's Mixins load
        // while developing/testing this mod.
        project.getTasks().named("runClient", JavaExec.class,
                t -> t.args("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker"));
        project.getTasks().named("runServer", JavaExec.class,
                t -> t.args("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker"));

        JavaCompile compileJava = project.getTasks().named("compileJava", JavaCompile.class).get();
        String outSrgFile = new File(compileJava.getTemporaryDir(), "outSrg.srg").getAbsolutePath();
        String outRefMapFile = new File(compileJava.getTemporaryDir(), "mixins." + strProp("modId") + ".refmap.json").getAbsolutePath();

        if (!beneath) {
            // Normal Mixin mode: this mod owns mixins.<modId>.json.
            // Manifest attributes, one by one:
            //   TweakClass                 -> Mixin runtime bootstrap; makes this jar a
            //                                 coremod when launched standalone.
            //   MixinConfigs               -> declares THIS mod's own Mixin config; the
            //                                 reason a mixins.<modId>.json must exist in
            //                                 the jar (auto-generated when missing).
            //   ForceLoadAsMod             -> coremod/FML loading: force-load the coremod
            //                                 jar as a regular mod too.
            //   FMLCorePluginContainsFMLMod-> coremod/FML loading: tells FML the coremod
            //                                 jar also contains an @Mod class.
            project.getTasks().named("jar", Jar.class, jar -> {
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("TweakClass", "org.spongepowered.asm.launch.MixinTweaker");
                attrs.put("MixinConfigs", "mixins." + strProp("modId") + ".json");
                attrs.put("ForceLoadAsMod", "true");
                attrs.put("FMLCorePluginContainsFMLMod", "true");
                jar.getManifest().attributes(attrs);

                // GTNH-style early mixins: an IEarlyMixinLoader coremod
                // (mixins.<modId>.early.json) is discovered by GTNHMixins/UniMixins
                // through FML's coremod list, so it must be registered via the
                // FMLCorePlugin manifest attribute. Optional, see coreModClass in
                // gradle.properties.
                String coreModClass = strProp("coreModClass");
                if (!coreModClass.isEmpty()) {
                    jar.getManifest().attributes(Collections.singletonMap(
                            "FMLCorePlugin", strProp("modGroupId") + "." + coreModClass));
                }
                jar.from(outRefMapFile);
            });

            // Feed the Mixin annotation processor with SRG mappings and refmap output
            project.getTasks().named("compileJava", JavaCompile.class, t -> {
                ReobfTask reobf = (ReobfTask) project.getTasks().getByName("reobf");
                t.getOptions().getCompilerArgs().add("-AreobfSrgFile=" + reobf.getSrg());
                t.getOptions().getCompilerArgs().add("-AoutSrgFile=" + outSrgFile);
                t.getOptions().getCompilerArgs().add("-AoutRefMapFile=" + outRefMapFile);
            });

            project.getTasks().named("reobf", ReobfTask.class, t -> t.addExtraSrgFile(outSrgFile));
        } else {
            // Beneath Mixin mode: deliberately NO 'TweakClass', NO 'MixinConfigs',
            // NO 'ForceLoadAsMod', NO 'FMLCorePluginContainsFMLMod' manifest
            // attributes. This mod is not a Mixin config owner and not a coremod;
            // the Mixin runtime is supplied by the UniMixins dependency jar instead,
            // so nothing will ever search for a missing mixins.<modId>.json.
            System.out.println(">>> Beneath Mixin mode: Mixin runtime kept for prerequisite mods, no MixinConfigs/refmap for this mod");

            // Friendly heads-up: a leftover Mixin config is not packaged in this mode
            // (processResources excludes mixins.*.json), so it is dead code.
            List<File> leftoverConfigs = project.fileTree("src/main/resources")
                    .matching(spec -> spec.include("mixins.*.json")).getFiles().stream().collect(Collectors.toList());
            if (!leftoverConfigs.isEmpty()) {
                System.out.println(">>> WARNING: Beneath Mixin mode is on, but Mixin config(s) still exist: " +
                        leftoverConfigs.stream().map(File::getName).collect(Collectors.joining(", ")) +
                        " - they will NOT be packaged. Delete them, or set enableBeneathMixin=false if this mod owns Mixins.");
            }
        }
    }

    // === === === Developer Account (GTNH style) === === ===
    // Fixed developer account for runClient:
    //   * The player name comes from developmentEnvironmentUserName
    //     (default "developer") instead of a random "Player"
    //   * The UUID is deterministically derived from the username via
    //     UUID.nameUUIDFromBytes (the offline fallback used by GTNH
    //     RetroFuturaGradle), so the same name always maps to the same UUID
    //   * Different developers just use different names;
    //     temporary override: gradlew runClient -PdevName=Alice
    private void configureDevAccount() {
        String devName = strProp("devName", strProp("developmentEnvironmentUserName", "developer"));
        String devUUID = strProp("developmentEnvironmentUUID",
                java.util.UUID.nameUUIDFromBytes(devName.getBytes(StandardCharsets.UTF_8)).toString());

        project.getTasks().named("runClient", JavaExec.class, t -> {
            t.doFirst(x -> System.out.println(">>> Starting Minecraft with developer account: " + devName + " (UUID: " + devUUID + ")"));
            t.args("--username", devName);
            t.args("--uuid", devUUID);
        });
    }

    // === === === compile settings === === ===

    private void configureCompileSettings(boolean kotlin) {
        JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
        javaExt.setSourceCompatibility("1.8");
        javaExt.setTargetCompatibility("1.8");

        project.getTasks().withType(JavaCompile.class).configureEach(t -> {
            t.getOptions().setEncoding("UTF-8");
            t.getOptions().getCompilerArgs().addAll(Arrays.asList("-Xlint:unchecked", "-Xlint:deprecation", "-Xlint:all"));
        });

        if (kotlin) {
            // Kotlin JVM target must match the Java target (1.8). Referenced via
            // reflection because the Kotlin Gradle plugin is not on the buildSrc
            // compile classpath - it only exists on the root project's buildscript.
            project.getTasks().configureEach(t -> {
                if (t.getClass().getName().equals("org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile")) {
                    try {
                        Object compilerOptions = t.getClass().getMethod("getCompilerOptions").invoke(t);
                        Class<?> jvmTarget = Class.forName("org.jetbrains.kotlin.gradle.dsl.JvmTarget");
                        compilerOptions.getClass().getMethod("setJvmTarget", jvmTarget)
                                .invoke(compilerOptions, Enum.valueOf((Class<Enum>) jvmTarget, "JVM_1_8"));
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to configure Kotlin JVM target", e);
                    }
                }
            });
        }
    }

    // === === === auxiliary jars & tasks === === ===

    private void registerJars() {
        SourceSet main = sourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Map<String, Object> jarManifestAttrs = ((Jar) project.getTasks().getByName("jar")).getManifest().getAttributes();

        TaskProvider<Jar> deobfJar = project.getTasks().register("deobfJar", Jar.class, t -> {
            t.dependsOn("reobf");
            t.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
            t.getArchiveClassifier().set("deobf");
            t.from(main.getOutput());
            t.getManifest().attributes(jarManifestAttrs);
        });

        TaskProvider<Jar> sourcesJar = project.getTasks().register("sourcesJar", Jar.class, t -> {
            t.dependsOn("classes");
            t.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            t.getArchiveClassifier().set("deobf-sources");
            t.from(main.getAllSource());
            t.exclude("methods.bin", "McpToSrg.csv");
        });

        project.getTasks().register("updateGradleProperties", t -> t.doLast(x -> {
            File propsFile = project.file("gradle.properties");
            if (!propsFile.exists()) {
                throw new GradleException("gradle.properties Not Found");
            }
            String updated = readFile(propsFile).replaceAll("modVersion\\s*=.*\n",
                    "modVersion=" + project.property("modVersion") + "\n");
            writeFile(propsFile, updated);
            System.out.println(">>> Have upgraded modVersion=" + project.property("modVersion") + " from gradle.properties");
        }));

        project.getTasks().named("build").configure(t -> t.dependsOn("updateGradleProperties"));

        project.getArtifacts().add("archives", deobfJar);
        project.getArtifacts().add("archives", sourcesJar);
    }

    private void configureLicensePackaging() {
        if (!boolProp("include_license")) {
            return;
        }
        File license = project.file("./LICENSE");
        project.getTasks().named("jar", Jar.class, t -> t.from(license));
        project.getTasks().named("deobfJar", Jar.class, t -> t.from(license));
        project.getTasks().named("sourcesJar", Jar.class, t -> t.from(license));
    }

    // === === === publishing === === ===

    private void configurePublishing() {
        project.getPluginManager().apply("maven-publish");
        // Disable Gradle module metadata generation (.module files): the
        // publication must only produce a classic POM. The metadata tasks are
        // matched by name to avoid a hard dependency on their exact class.
        project.getTasks().configureEach(t -> {
            if (t.getName().startsWith("generateMetadataFileFor")) {
                t.setEnabled(false);
            }
        });

        project.getExtensions().configure(PublishingExtension.class, publishing -> {
            publishing.getPublications().create("mavenJava", MavenPublication.class, mp -> {
                mp.from(project.getComponents().getByName("java"));
                mp.artifact(project.getTasks().named("sourcesJar"));
                mp.artifact(project.getTasks().named("deobfJar"));
                mp.setGroupId(project.getGroup().toString());
                mp.setArtifactId(project.getExtensions().getByType(BasePluginExtension.class).getArchivesName().get());
                mp.setVersion(project.getVersion().toString());

                // Filter out dependencies without a groupId (e.g. forgeBin) from
                // the generated POM, so the published POM stays resolvable.
                mp.getPom().withXml(xml -> {
                    Node root = xml.asNode();
                    NodeList depsNode = (NodeList) root.get("dependencies");
                    if (depsNode.isEmpty()) {
                        return;
                    }
                    Node deps = (Node) depsNode.get(0);
                    List<Object> children = new ArrayList<>(deps.children());
                    for (Object child : children) {
                        if (!(child instanceof Node)) {
                            continue;
                        }
                        Node dep = (Node) child;
                        if (!isDependencyNode(dep)) {
                            continue;
                        }
                        NodeList groupIdNodes = (NodeList) dep.get("groupId");
                        if (groupIdNodes.isEmpty() || ((Node) groupIdNodes.get(0)).text().trim().isEmpty()) {
                            deps.remove(dep);
                        }
                    }
                });
            });
        });
    }

    // Node.name() may be a String or a groovy.namespace.QName depending on the
    // parser; normalize both to the local part for comparison.
    private static boolean isDependencyNode(Node node) {
        String name = node.name() instanceof groovy.namespace.QName
                ? ((groovy.namespace.QName) node.name()).getLocalPart()
                : node.name().toString();
        return "dependency".equals(name);
    }

    // === === === file IO === === ===

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new GradleException("Failed to read " + file, e);
        }
    }

    private static void writeFile(File file, String content) {
        try {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new GradleException("Failed to write " + file, e);
        }
    }
}
