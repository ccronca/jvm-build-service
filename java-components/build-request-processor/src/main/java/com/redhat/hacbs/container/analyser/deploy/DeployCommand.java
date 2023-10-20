package com.redhat.hacbs.container.analyser.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import jakarta.enterprise.inject.spi.BeanManager;

import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.container.analyser.dependencies.SBomGenerator;
import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;
import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.recipies.util.FileUtil;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.Contaminates;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "deploy")
public class DeployCommand implements Runnable {

    private static final String SLASH = "/";
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    private static final Set<String> ALLOWED_CONTAMINANTS = Set.of("-tests.jar");
    final BeanManager beanManager;
    final ResultsUpdater resultsUpdater;

    @CommandLine.Option(required = false, names = "--allowed-sources", defaultValue = "redhat,rebuilt", split = ",")
    Set<String> allowedSources;

    @CommandLine.Option(required = true, names = "--path")
    Path deploymentPath;

    @CommandLine.Option(required = false, names = "--task-run-name")
    String taskRun;

    @CommandLine.Option(required = true, names = "--source-path")
    Path sourcePath;

    @CommandLine.Option(required = false, names = "--logs-path")
    Path logsPath;

    @CommandLine.Option(required = false, names = "--build-info-path")
    Path buildInfoPath;

    @CommandLine.Option(required = true, names = "--scm-uri")
    String scmUri;

    @CommandLine.Option(required = true, names = "--scm-commit")
    String commit;

    @CommandLine.Option(names = "--registry-host", defaultValue = "quay.io")
    String host;
    @CommandLine.Option(names = "--registry-port", defaultValue = "443")
    int port;
    @CommandLine.Option(names = "--registry-owner", defaultValue = "hacbs")
    String owner;
    @ConfigProperty(name = "registry.token")
    Optional<String> token;
    @CommandLine.Option(names = "--registry-repository", defaultValue = "artifact-deployments")
    String repository;
    @CommandLine.Option(names = "--registry-insecure", defaultValue = "false")
    boolean insecure;

    @CommandLine.Option(names = "--image-id")
    String imageId;

    @CommandLine.Option(names = "--hermetic")
    boolean hermetic;

    @CommandLine.Option(names = "--registry-prepend-tag", defaultValue = "")
    String prependTag;
    protected String imageName;
    protected String imageDigest;

    public DeployCommand(BeanManager beanManager,
            ResultsUpdater resultsUpdater) {
        this.beanManager = beanManager;
        this.resultsUpdater = resultsUpdater;
    }

    public void run() {
        try {
            Set<String> gavs = new HashSet<>();

            Map<String, Set<String>> contaminatedPaths = new HashMap<>();
            Map<String, Set<String>> contaminatedGavs = new HashMap<>();
            // Represents directories that should not be deployed i.e. if a single artifact (barring test jars) is
            // contaminated then none of the artifacts will be deployed.
            Set<Path> toRemove = new HashSet<>();
            Map<Path, Gav> jarFiles = new HashMap<>();
            Files.walkFileTree(deploymentPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = deploymentPath.relativize(file).toString();
                    Optional<Gav> gav = getGav(name);
                    gav.ifPresent(
                            gav1 -> gavs.add(gav1.getGroupId() + ":" + gav1.getArtifactId() + ":" + gav1.getVersion()));
                    Log.debugf("Checking %s for contaminants with GAV %s", name, gav);
                    //we check every file as we also want to catch .tar.gz etc
                    var info = ClassFileTracker.readTrackingDataFromFile(Files.newInputStream(file), name);
                    for (var i : info) {
                        if (!allowedSources.contains(i.source)) {
                            Log.errorf("%s was contaminated by %s from %s", name, i.gav, i.source);
                            if (ALLOWED_CONTAMINANTS.stream().noneMatch(a -> file.getFileName().toString().endsWith(a))) {
                                gav.ifPresent(g -> contaminatedGavs.computeIfAbsent(i.gav, s -> new HashSet<>())
                                        .add(g.getGroupId() + ":" + g.getArtifactId() + ":" + g.getVersion()));
                                int index = name.lastIndexOf("/");
                                if (index != -1) {
                                    contaminatedPaths.computeIfAbsent(name.substring(0, index),
                                            s -> new HashSet<>()).add(i.gav);
                                } else {
                                    contaminatedPaths.computeIfAbsent("", s -> new HashSet<>()).add(i.gav);
                                }
                                toRemove.add(file.getParent());
                            } else {
                                Log.debugf("Ignoring contaminant for %s", file.getFileName());
                            }
                        }
                    }
                    if (gav.isPresent()) {
                        //now add our own tracking data
                        if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                            jarFiles.put(file, gav.get());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            for (var e : jarFiles.entrySet()) {
                Path file = e.getKey();
                Gav gav = e.getValue();
                try {
                    String fileName = file.getFileName().toString();
                    Path temp = file.getParent().resolve(fileName + ".temp");
                    ClassFileTracker.addTrackingDataToJar(Files.newInputStream(file),
                            new TrackingData(
                                    gav.getGroupId() + ":" + gav.getArtifactId() + ":"
                                            + gav.getVersion(),
                                    "rebuilt",
                                    Map.of("scm-uri", scmUri, "scm-commit", commit, "hermetic",
                                            Boolean.toString(hermetic))),
                            Files.newOutputStream(temp), false);
                    Files.delete(file);
                    Files.move(temp, file);
                    try (Stream<Path> pathStream = Files.list(file.getParent())) {
                        pathStream.filter(s -> s.getFileName().toString().startsWith(fileName + "."))
                                .forEach(f -> {
                                    try {
                                        Files.delete(f);
                                    } catch (IOException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                });
                    }

                    Files.writeString(file.getParent().resolve(fileName + ".md5"),
                            HashUtil.md5(Files.newInputStream(file)));
                    Files.writeString(file.getParent().resolve(fileName + ".sha1"),
                            HashUtil.sha1(Files.newInputStream(file)));
                } catch (Exception ex) {
                    Log.errorf(ex, "Failed to instrument %s", file);
                }
            }
            for (var i : toRemove) {
                Log.errorf("Removing %s as it is contaminated", i);
                FileUtil.deleteRecursive(i);
            }

            //update the DB with contaminant information
            Log.infof("Contaminants: %s", contaminatedPaths);
            Log.infof("Contaminated GAVS: %s", contaminatedGavs);
            Log.infof("GAVs to deploy: %s", gavs);
            if (gavs.isEmpty()) {
                Log.errorf("No content to deploy found in deploy directory");

                Files.walkFileTree(deploymentPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Log.errorf("Contents: %s", file);
                        return FileVisitResult.CONTINUE;
                    }
                });
                throw new RuntimeException("deploy failed");
            }
            //we still deploy, but without the contaminates
            java.nio.file.Path deployFile = deploymentPath;
            // This means the build failed to produce any deployable output.
            // If everything is contaminated we still need the task to succeed so we can resolve the contamination.
            for (var i : contaminatedGavs.entrySet()) {
                gavs.removeAll(i.getValue());
            }
            generateBuildSbom();

            if (!gavs.isEmpty()) {
                try {
                    cleanBrokenSymlinks(sourcePath);
                    doDeployment(deployFile, sourcePath, logsPath, gavs);
                } catch (Throwable t) {
                    Log.error("Deployment failed", t);
                    flushLogs();
                    throw t;
                }
            } else {
                Log.errorf("Skipped deploying from task run %s as all artifacts were contaminated", taskRun);
            }
            if (taskRun != null) {

                List<Contaminates> newContaminates = new ArrayList<>();
                for (var i : contaminatedGavs.entrySet()) {
                    Contaminates contaminates = new Contaminates();
                    contaminates.setContaminatedArtifacts(new ArrayList<>(i.getValue()));
                    contaminates.setGav(i.getKey());
                    newContaminates.add(contaminates);
                }
                String serialisedContaminants = ResultsUpdater.MAPPER.writeValueAsString(newContaminates);
                Log.infof("Updating results %s with contaminants %s and deployed resources %s",
                        taskRun, serialisedContaminants, gavs);
                resultsUpdater.updateResults(taskRun, Map.of(
                        "CONTAMINANTS", serialisedContaminants,
                        "DEPLOYED_RESOURCES", String.join(",", gavs),
                        "IMAGE_URL", imageName == null ? "" : imageName,
                        "IMAGE_DIGEST", imageDigest == null ? "" : "sha256:" + imageDigest));
            }
        } catch (Exception e) {
            Log.error("Deployment failed", e);
            throw new RuntimeException(e);
        }
    }

    private void generateBuildSbom() {
        if (buildInfoPath == null) {
            Log.infof("Not generating build sbom, path not set");
            return;
        }
        Log.infof("Generating build sbom from %s", buildInfoPath);
        Set<TrackingData> data = new HashSet<>();
        try {
            Files.walkFileTree(buildInfoPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try (InputStream inputStream = Files.newInputStream(file)) {
                        Set<TrackingData> ret = ClassFileTracker.readTrackingDataFromFile(inputStream,
                                file.getFileName().toString());
                        if (!ret.isEmpty()) {
                            Log.infof("Found file at %s", file);
                            data.addAll(ret);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
            });
            var sbom = SBomGenerator.generateSBom(data, null);
            var json = BomGeneratorFactory.createJson(CycloneDxSchema.Version.VERSION_12, sbom);
            String sbomStr = json.toJsonString();
            Log.infof("Build Sbom \n%s", sbomStr);
            Files.writeString(logsPath.resolve("build-sbom.json"), sbomStr, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.errorf(e, "Failed to generate build sbom");
        }
    }

    private void cleanBrokenSymlinks(Path sourcePath) throws IOException {
        Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                try (var s = Files.list(dir)) {
                    List<Path> paths = s.toList();
                    for (var i : paths) {
                        //broken symlinks will fail this check
                        if (!Files.exists(i)) {
                            Files.delete(i);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

    }

    protected void doDeployment(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs) throws Exception {

        ContainerRegistryDeployer deployer = new ContainerRegistryDeployer(host, port, owner, token.orElse(""), repository,
                insecure,
                prependTag, imageId);
        deployer.deployArchive(deployDir, sourcePath, logsPath, gavs, new BiConsumer<String, String>() {
            @Override
            public void accept(String s, String hash) {
                imageName = s;
                imageDigest = hash;
            }
        });
    }

    private void flushLogs() {
        System.err.flush();
        System.out.flush();
    }

    private Optional<Gav> getGav(String entryName) {
        if (entryName.startsWith("./")) {
            entryName = entryName.substring(2);
        }
        if (entryName.endsWith(DOT_JAR) || entryName.endsWith(DOT_POM)) {

            List<String> pathParts = List.of(entryName.split(SLASH));
            int numberOfParts = pathParts.size();

            String version = pathParts.get(numberOfParts - 2);
            String artifactId = pathParts.get(numberOfParts - 3);
            List<String> groupIdList = pathParts.subList(0, numberOfParts - 3);
            String groupId = String.join(DOT, groupIdList);

            return Optional.of(Gav.create(groupId, artifactId, version));
        }
        return Optional.empty();
    }

}
