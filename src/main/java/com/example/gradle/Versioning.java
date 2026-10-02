package com.example.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.process.ExecResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioning - mod metadata versioning (two modes).
 *
 * Mode 1 - Tags.java extraction (needTagsToIdentify=true, default):
 *   * Locates Tags.java (gradle.properties "tags_file_path" or auto-scan src/)
 *   * Extracts VERSION / NAME / MODID constants via regex
 *   * Fills project.version / modVersion / modName / modId
 *
 * Mode 2 - Git tag versioning (enableGitTagVersioning=true, GTNH style):
 *   * Derives the version from git, mimicking GTNH's GitVersionModule:
 *     clean tag = tag itself; commits ahead of the tag =
 *     "tag-&lt;branch&gt;.&lt;distance&gt;+&lt;hash&gt;[-dirty]"; no tag/no repo = "NO-GIT-TAG-SET"
 *   * Rewrites the VERSION constant inside Tags.java automatically
 *     (NAME/MODID are kept; the file is created if missing)
 *   * A VERSION environment variable overrides the git-derived version
 *   * An optional "versionPattern" regex validates clean-tag releases
 *   * Takes precedence: needTagsToIdentify is ignored while this mode is on
 *
 * Runs during the configuration phase, before processResources is wired up,
 * so project.version is final by the time resources are processed.
 */
public final class Versioning {

    private Versioning() {
    }

    /** Entry point, invoked from TemplatePlugin.apply. */
    public static void apply(Project project) {
        boolean useGitTagVersioning = project.hasProperty("enableGitTagVersioning")
                && Boolean.parseBoolean(project.property("enableGitTagVersioning").toString());

        if (useGitTagVersioning) {
            // Git tag mode takes full control; needTagsToIdentify is ignored here.
            applyGitTagVersioning(project);
        } else {
            boolean needTags = !project.hasProperty("needTagsToIdentify")
                    || Boolean.parseBoolean(project.property("needTagsToIdentify").toString());

            if (needTags) {
                TagsData tags = extractTagsFromJava(project);
                project.getExtensions().getExtraProperties().set("modVersion", tags.version);
                project.setVersion(tags.version);
                if (tags.name != null) {
                    project.getExtensions().getExtraProperties().set("modName", tags.name);
                }
                if (tags.modid != null) {
                    project.getExtensions().getExtraProperties().set("modId", tags.modid);
                }

                System.out.println(">>> Auto-read from " + tags.path + ":");
                System.out.println(">>>   VERSION = " + tags.version);
                if (tags.name != null) {
                    System.out.println(">>>   NAME    = " + tags.name);
                }
                if (tags.modid != null) {
                    System.out.println(">>>   MODID   = " + tags.modid);
                }
            } else {
                System.out.println(">>> needTagsToIdentify=false, using values from gradle.properties");
            }
        }
    }

    // Prefer the path specified in gradle.properties, otherwise auto-scan src/.
    private static TagsData extractTagsFromJava(Project project) {
        String tagsFilePath = project.hasProperty("tags_file_path") ? project.property("tags_file_path").toString() : null;
        File tagsFile;

        if (tagsFilePath != null) {
            tagsFile = project.file(tagsFilePath);
            if (!tagsFile.exists()) {
                throw new GradleException("Tags.java not found at specified path: " + tagsFile);
            }
        } else {
            Set<File> tagsFiles = project.fileTree("src").matching(spec -> spec.include("**/Tags.java")).getFiles();

            if (tagsFiles.isEmpty()) {
                throw new GradleException("Tags.java not found under src/ directory");
            }
            if (tagsFiles.size() > 1) {
                throw new GradleException("Multiple Tags.java found under src/: " + tagsFiles);
            }
            tagsFile = tagsFiles.iterator().next();
        }

        String tagsText = readFile(tagsFile);
        Matcher nameMatcher = Pattern.compile("NAME\\s*=\\s*\"([^\"]+)\"").matcher(tagsText);
        Matcher modidMatcher = Pattern.compile("MODID\\s*=\\s*\"([^\"]+)\"").matcher(tagsText);
        Matcher versionMatcher = Pattern.compile("VERSION\\s*=\\s*\"([^\"]+)\"").matcher(tagsText);

        if (!versionMatcher.find()) {
            throw new GradleException("VERSION not found in " + tagsFile.getPath());
        }

        TagsData data = new TagsData();
        data.version = versionMatcher.group(1);
        data.name = nameMatcher.find() ? nameMatcher.group(1) : null;
        data.modid = modidMatcher.find() ? modidMatcher.group(1) : null;
        data.path = tagsFile.getPath();
        return data;
    }

    // Resolve the Tags.java path for WRITING. Unlike extraction mode this does
    // not fail when the file is missing - it is generated on demand. The
    // package line is recovered from an existing file or derived from
    // modGroupId.
    private static File resolveTagsFileForWrite(Project project) {
        String tagsFilePath = project.hasProperty("tags_file_path") ? project.property("tags_file_path").toString() : null;

        if (tagsFilePath != null) {
            return project.file(tagsFilePath);
        }

        Set<File> tagsFiles = project.fileTree("src").matching(spec -> spec.include("**/Tags.java")).getFiles();

        if (tagsFiles.size() > 1) {
            throw new GradleException("Multiple Tags.java found under src/: " + tagsFiles);
        }
        if (!tagsFiles.isEmpty()) {
            return tagsFiles.iterator().next();
        }

        // No Tags.java yet: place it under the modGroupId package directory
        String packagePath = project.property("modGroupId").toString().replace('.', '/');
        return project.file("src/main/java/" + packagePath + "/Tags.java");
    }

    // Rewrite the VERSION constant of Tags.java to the given version.
    // Keeps NAME/MODID when the file exists; otherwise generates the whole
    // class from modName / modId in gradle.properties.
    private static void writeTagsVersion(Project project, File tagsFile, String version) {
        String template = "package " + project.property("modGroupId") + ";\n" +
                "\n" +
                "/**\n" +
                " * Mod metadata.\n" +
                " *\n" +
                " * VERSION is maintained by the build tooling (enableGitTagVersioning=true),\n" +
                " * driven by git tags.\n" +
                " */\n" +
                "public class Tags {\n" +
                "    public static final String NAME = \"" + project.property("modName") + "\";\n" +
                "    public static final String MODID = \"" + project.property("modId") + "\";\n" +
                "    public static final String VERSION = \"" + version + "\";\n" +
                "}\n";

        if (tagsFile.exists()) {
            String tagsText = readFile(tagsFile);
            Matcher versionMatcher = Pattern.compile("VERSION\\s*=\\s*\"([^\"]*)\"").matcher(tagsText);
            if (versionMatcher.find()) {
                String updated = tagsText.replaceFirst("VERSION\\s*=\\s*\"[^\"]*\"",
                        Matcher.quoteReplacement("VERSION = \"" + version + "\""));
                if (!tagsText.equals(updated)) {
                    writeFile(tagsFile, updated);
                    System.out.println(">>> Updated VERSION to " + version + " in " + tagsFile.getPath());
                }
                return;
            }
            // VERSION constant missing - fall through and regenerate the whole file
        } else {
            tagsFile.getParentFile().mkdirs();
            System.out.println(">>> Generated " + tagsFile.getPath());
        }
        writeFile(tagsFile, template);
    }

    // Apply the git-derived version to the project and rewrite Tags.java.
    // Called before the needTagsToIdentify branch so it fully takes over.
    private static void applyGitTagVersioning(Project project) {
        GitVersionInfo gitInfo = getVersionFromGit(project);
        String version = gitInfo.version;

        // Optional release-tag format check (GTNH-style "versionPattern")
        if (gitInfo.checkable && project.hasProperty("versionPattern")
                && !project.property("versionPattern").toString().isEmpty()) {
            String rawPattern = project.property("versionPattern").toString();
            if (!version.matches(rawPattern)) {
                throw new GradleException("Invalid version '" + version + "' does not match version pattern '" + rawPattern + "'");
            }
        }

        project.getExtensions().getExtraProperties().set("modVersion", version);
        project.setVersion(version);

        writeTagsVersion(project, resolveTagsFileForWrite(project), version);

        System.out.println(">>> Git tag versioning: project.version = " + version);
    }

    // Derive the mod version from git, mirroring GTNH's GitVersionModule (RFG):
    //   * clean tag                    -> the tag itself (e.g. "1.2.0")
    //   * commits ahead of the tag     -> "tag-<branch>.<distance>+<hash>[-dirty]"
    //   * dirty working tree on a tag  -> "tag-<branch>+<hash>-dirty"
    //   * no tag at all                -> short commit hash (fallback)
    //   * git failure / no repository  -> "NO-GIT-TAG-SET"
    // checkable is true only for a clean, exact tag so the optional
    // versionPattern applies only to real releases.
    private static GitVersionInfo getVersionFromGit(Project project) {
        String envOverride = System.getenv("VERSION");
        if (envOverride != null && !envOverride.trim().isEmpty()) {
            System.out.println(">>> Using version from VERSION environment variable: " + envOverride.trim());
            return new GitVersionInfo(envOverride.trim(), false);
        }

        GitResult hash = execGit(project, "rev-parse", "--short=8", "HEAD");
        if (!hash.ok) {
            // Not a git repository (e.g. downloaded as ZIP) or git unavailable
            System.out.println(">>> WARNING: git unavailable or not a git repository - using NO-GIT-TAG-SET");
            return new GitVersionInfo("NO-GIT-TAG-SET", false);
        }

        GitResult describe = execGit(project, "describe", "--tags", "--dirty", "--always");
        String described = describe.ok ? describe.text : "";
        boolean dirty = described.endsWith("-dirty");
        if (dirty) {
            described = described.substring(0, described.length() - "-dirty".length());
        }

        // Whether any tag exists at all: with --always a tagless repo falls
        // back to the bare commit hash, which we can detect with --abbrev=40
        GitResult fullHash = execGit(project, "describe", "--tags", "--always", "--abbrev=40");
        boolean hasAnyTag = fullHash.ok && !fullHash.text.matches("[0-9a-f]+");
        if (!describe.ok || !hasAnyTag) {
            System.out.println(">>> WARNING: no git tags found - falling back to commit hash " + hash.text);
            return new GitVersionInfo(hash.text + (dirty ? "-dirty" : ""), false);
        }

        GitResult branch = execGit(project, "rev-parse", "--abbrev-ref", "HEAD");
        String branchName = branch.ok ? branch.text
                : (System.getenv("GIT_BRANCH") != null ? System.getenv("GIT_BRANCH") : "git");
        branchName = branchName.startsWith("origin/") ? branchName.substring("origin/".length()) : branchName;
        branchName = branchName.replaceAll("[^a-zA-Z0-9-]+", "-"); // sanitize for semver

        // "git describe" format: <tag>-<distance>-g<hash> or plain <tag>
        Matcher m = Pattern.compile("^(.*)-(\\d+)-g([0-9a-f]+)$").matcher(described);
        String identifiedVersion;
        boolean checkable = false;

        if (m.matches()) {
            String tag = m.group(1);
            String distance = m.group(2);
            identifiedVersion = tag + "-" + branchName + "." + distance + "+" + hash.text + (dirty ? "-dirty" : "");
        } else {
            // Exactly on a tag
            identifiedVersion = described;
            if (!dirty) {
                checkable = true;
            } else {
                identifiedVersion = described + "-" + branchName + "+" + hash.text + "-dirty";
            }
        }
        return new GitVersionInfo(identifiedVersion, checkable);
    }

    private static GitResult execGit(Project project, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult result = project.exec(spec -> {
            spec.commandLine(Arrays.asList(args));
            spec.setStandardOutput(out);
            spec.setIgnoreExitValue(true);
        });
        GitResult r = new GitResult();
        r.ok = result.getExitValue() == 0;
        r.text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        return r;
    }

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

    private static final class TagsData {
        String version;
        String name;
        String modid;
        String path;
    }

    private static final class GitResult {
        boolean ok;
        String text;
    }

    private static final class GitVersionInfo {
        final String version;
        final boolean checkable;

        GitVersionInfo(String version, boolean checkable) {
            this.version = version;
            this.checkable = checkable;
        }
    }
}
