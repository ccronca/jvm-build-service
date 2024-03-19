package com.redhat.hacbs.artifactcache.services.client.ociregistry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryUnauthorizedException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.http.ResponseException;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;
import com.redhat.hacbs.artifactcache.services.StorageManager;
import com.redhat.hacbs.resources.util.ShaUtil;

import io.quarkus.logging.Log;

public class OCIRegistryRepositoryClient implements RepositoryClient {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private final String registry;
    private final String owner;
    private final Optional<String> prependHashedGav;
    private final String repository;
    private final boolean enableHttpAndInsecureFailover;
    private final StorageManager storageManager;
    private final Credential credential;

    final RebuiltArtifacts rebuiltArtifacts;

    final Map<String, CountDownLatch> locks = new ConcurrentHashMap<>();

    public OCIRegistryRepositoryClient(String registry, String owner, String repository, Optional<String> authToken,
            Optional<String> prependHashedGav,
            boolean enableHttpAndInsecureFailover, RebuiltArtifacts rebuiltArtifacts,
            StorageManager storageManager) {
        this.prependHashedGav = prependHashedGav;
        this.registry = registry;
        this.owner = owner;
        this.repository = repository;
        this.enableHttpAndInsecureFailover = enableHttpAndInsecureFailover;
        this.rebuiltArtifacts = rebuiltArtifacts;
        if (enableHttpAndInsecureFailover) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        if (authToken.isPresent() && !authToken.get().isBlank()) {
            if (authToken.get().trim().startsWith("{")) {
                //we assume this is a .dockerconfig file
                try (var parser = MAPPER.createParser(authToken.get())) {
                    DockerConfig config = parser.readValueAs(DockerConfig.class);
                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    String host = null;
                    String fullName = registry + "/" + owner + "/" + repository;
                    for (var i : config.getAuths().entrySet()) {
                        if (fullName.startsWith(i.getKey())) {
                            found = true;
                            var decodedAuth = new String(Base64.getDecoder().decode(i.getValue().getAuth()),
                                    StandardCharsets.UTF_8);
                            int pos = decodedAuth.indexOf(":");
                            tmpUser = decodedAuth.substring(0, pos);
                            tmpPw = decodedAuth.substring(pos + 1);
                            host = i.getKey();
                            break;
                        }
                    }
                    if (!found) {
                        throw new RuntimeException("Unable to find a host matching " + registry
                                + " in provided dockerconfig, hosts provided: " + config.getAuths().keySet());
                    }
                    credential = Credential.from(tmpUser, tmpPw);
                    Log.infof("Credential provided as .dockerconfig, selected host %s for registry %s", host, registry);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var decoded = new String(Base64.getDecoder().decode(authToken.get()), StandardCharsets.UTF_8);
                int pos = decoded.indexOf(":");
                credential = Credential.from(decoded.substring(0, pos), decoded.substring(pos + 1));
                Log.infof("Credential provided as base64 encoded token");
            }
        } else {
            credential = null;
            Log.infof("No credential provided");
        }
        this.storageManager = storageManager;
    }

    @Override
    public String getName() {
        return registry;
    }

    @Override
    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version,
            String target) {
        long time = System.currentTimeMillis();

        group = group.replace("/", ".");
        String groupPath = group.replace(DOT, File.separator);
        String hashedGav = ShaUtil.sha256sum(group, artifact, version);
        if (prependHashedGav.isPresent()) {
            hashedGav = prependHashedGav.get() + UNDERSCORE + hashedGav;
        }
        if (hashedGav.length() > 128) {
            hashedGav = hashedGav.substring(0, 128);
        }

        String gav = group + ":" + artifact + ":" + version;
        if (!rebuiltArtifacts.isPossiblyRebuilt(gav)) {
            return Optional.empty();
        }
        Log.debugf("Attempting to retrieve %s for artifact %s", hashedGav, gav);
        RegistryClient registryClient = getRegistryClient();

        try {
            return doDownload(group, artifact, version, target, time, groupPath, hashedGav, gav, registryClient);
        } catch (RegistryUnauthorizedException e) {
            try {
                //this is quay specific possibly?
                //unfortunately we can't get the actual header
                String wwwAuthenticate = "Bearer realm=\"https://" + registry + "/v2/auth\",service=\"" + registry
                        + "\",scope=\"repository:" + owner + "/" + repository + ":pull\"";
                registryClient.authPullByWwwAuthenticate(wwwAuthenticate);
                return doDownload(group, artifact, version, target, time, groupPath, hashedGav, gav, registryClient);
            } catch (RegistryUnauthorizedException ex) {
                Log.errorf("Failed to authenticate against registry %s/%s/%s", registry, owner, repository);
                return Optional.empty();
            } catch (RegistryException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private Optional<ArtifactResult> doDownload(String group, String artifact, String version, String target, long time,
            String groupPath, String hashedGav, String gav, RegistryClient registryClient)
            throws RegistryUnauthorizedException {
        try {
            ManifestAndDigest<ManifestTemplate> manifestAndDigest = registryClient.pullManifest(hashedGav,
                    ManifestTemplate.class);

            ManifestTemplate manifest = manifestAndDigest.getManifest();
            DescriptorDigest descriptorDigest = manifestAndDigest.getDigest();

            String digestHash = descriptorDigest.getHash();
            Optional<Path> repoRoot = getLocalCachePath(registryClient, manifest, digestHash);
            if (repoRoot.isPresent()) {
                Path fileWeAreAfter = repoRoot.get().resolve(groupPath).resolve(artifact).resolve(version).resolve(target);

                boolean exists = Files.exists(fileWeAreAfter);
                if (exists) {
                    return Optional.of(
                            new ArtifactResult(null, Files.newInputStream(fileWeAreAfter), Files.size(fileWeAreAfter),
                                    getSha1(fileWeAreAfter),
                                    Map.of()));
                } else {
                    Log.warnf("Key %s:%s:%s not found", group, artifact, version);
                }
            }
        } catch (RegistryUnauthorizedException ioe) {
            throw ioe;
        } catch (IOException | RegistryException ioe) {
            Throwable cause = ioe.getCause();
            while (cause != null) {
                if (cause instanceof ResponseException) {
                    ResponseException e = (ResponseException) cause;
                    if (e.getStatusCode() == 404) {
                        Log.debugf("Failed to find artifact %s", hashedGav, gav);
                        return Optional.empty();
                    }
                }
                cause = cause.getCause();
            }
            throw new RuntimeException(ioe);
        } finally {
            Log.debugf("OCI registry request to %s:%s:%s took %sms", group, artifact, version,
                    System.currentTimeMillis() - time);
        }

        return Optional.empty();
    }

    @Override
    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        return Optional.empty();
    }

    private RegistryClient getRegistryClient() {
        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(), registry,
                owner + "/" + repository,
                new FailoverHttpClient(enableHttpAndInsecureFailover, enableHttpAndInsecureFailover,
                        s -> Log.info(s.getMessage())));

        if (credential != null) {
            factory.setCredential(credential);
        }

        return factory.newRegistryClient();
    }

    private Optional<Path> getLocalCachePath(RegistryClient registryClient, ManifestTemplate manifest, String digestHash)
            throws IOException {
        Path digestHashPath = storageManager.accessDirectory(digestHash);
        Path artifactsPath = Paths.get(digestHashPath.toString(), ARTIFACTS);
        if (existInLocalCache(digestHashPath)) {
            return Optional.of(artifactsPath);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            var existing = locks.putIfAbsent(digestHash, latch);
            if (existing == null) {
                try {
                    return pullFromRemoteAndCache(registryClient, manifest, digestHash, digestHashPath);
                } finally {
                    latch.countDown();
                    locks.remove(digestHash);
                }
            } else {
                try {
                    existing.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (existInLocalCache(digestHashPath)) {
                    return Optional.of(artifactsPath);
                }
                return Optional.empty();
            }
        }
    }

    private Optional<Path> pullFromRemoteAndCache(RegistryClient registryClient, ManifestTemplate manifest, String digestHash,
            Path digestHashPath)
            throws IOException {
        String manifestMediaType = manifest.getManifestMediaType();

        if (OCI_MEDIA_TYPE.equalsIgnoreCase(manifestMediaType)) {
            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = ((OciManifestTemplate) manifest).getLayers();
            if (layers.size() == 3) {
                // Layer 2 is artifacts
                BuildableManifestTemplate.ContentDescriptorTemplate artifactsLayer = layers.get(2);

                Blob blob = registryClient.pullBlob(artifactsLayer.getDigest(), s -> {
                }, s -> {
                });

                Path outputPath = Files.createDirectories(digestHashPath);

                Path tarFile = Files.createFile(Paths.get(outputPath.toString(), digestHash + ".tar"));
                try (OutputStream tarOutputStream = Files.newOutputStream(tarFile)) {
                    blob.writeTo(tarOutputStream);
                }
                try (InputStream tarInput = Files.newInputStream(tarFile)) {
                    extractTarArchive(tarInput, outputPath.toString());
                    return Optional.of(Paths.get(outputPath.toString(), ARTIFACTS));
                }
            } else {
                Log.warnf("Unexpected layer size %d. We expect 3", layers.size());
                return Optional.empty();
            }
        } else {
            // TODO: handle docker type?
            // application/vnd.docker.distribution.manifest.v2+json = V22ManifestTemplate
            throw new RuntimeException(
                    "Wrong ManifestMediaType type. We support " + OCI_MEDIA_TYPE + ", but got " + manifestMediaType);
        }
    }

    private boolean existInLocalCache(Path digestHashPath) {
        return Files.exists(digestHashPath) && Files.isDirectory(digestHashPath)
                && Files.exists(digestHashPath.resolve(ARTIFACTS));
    }

    private Optional<String> getSha1(Path file) throws IOException {
        Path shaFile = Paths.get(file.toString() + DOT + SHA_1);
        boolean exists = Files.exists(shaFile);
        if (exists) {
            return Optional.of(Files.readString(shaFile));
        }
        return Optional.empty();
    }

    private void extractTarArchive(InputStream tarInput, String folder) throws IOException {
        try (GZIPInputStream inputStream = new GZIPInputStream(tarInput);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {

            for (TarArchiveEntry entry = tarArchiveInputStream.getNextEntry(); entry != null; entry = tarArchiveInputStream
                    .getNextEntry()) {
                extractEntry(entry, tarArchiveInputStream, folder);
            }
        }
    }

    private void extractEntry(ArchiveEntry entry, InputStream tar, String folder) throws IOException {
        final int bufferSize = 4096;
        final String path = folder + File.separator + entry.getName();
        if (entry.isDirectory()) {
            new File(path).mkdirs();
        } else {
            int count;
            byte[] data = new byte[bufferSize];
            try (FileOutputStream os = new FileOutputStream(path);
                    BufferedOutputStream dest = new BufferedOutputStream(os, bufferSize)) {
                while ((count = tar.read(data, 0, bufferSize)) != -1) {
                    dest.write(data, 0, count);
                }
            }
        }
    }

    private static final String UNDERSCORE = "_";
    private static final String ARTIFACTS = "artifacts";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    private static final String OCI_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
}
