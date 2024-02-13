package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;

@ApplicationScoped
public class RebuiltArtifacts {

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    final List<RebuiltArtifactDeletionListener> imageDeletionListeners = Collections.synchronizedList(new ArrayList<>());

    final Set<String> gavs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @PostConstruct
    void setup() {

        if (LaunchMode.current() == LaunchMode.TEST || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate RebuiltArtifacts");
            return;
        }

        client.resources(RebuiltArtifact.class).inform().addEventHandler(new ResourceEventHandler<RebuiltArtifact>() {
            @Override
            public void onAdd(RebuiltArtifact artifactBuild) {
                Log.infof("Adding new RebuiltArtifact %s", artifactBuild.getSpec().getGav());
                gavs.add(artifactBuild.getSpec().getGav());
            }

            @Override
            public void onUpdate(RebuiltArtifact old, RebuiltArtifact newObj) {
                List<RebuiltArtifactDeletionListener> listeners = new ArrayList<>(imageDeletionListeners.size());
                synchronized (imageDeletionListeners) {
                    listeners.addAll(imageDeletionListeners);
                }
                for (var i : listeners) {
                    try {
                        i.rebuiltArtifactDeleted(old.getSpec().getGav(), old.getSpec().getDigest());
                    } catch (Throwable t) {
                        Log.errorf(t, "Failed to notify deletion listener");
                    }
                }
                Log.infof("Adding updated RebuiltArtifact %s", newObj.getSpec().getGav());
                gavs.add(newObj.getSpec().getGav());
            }

            @Override
            public void onDelete(RebuiltArtifact artifactBuild, boolean deletedFinalStateUnknown) {
                gavs.remove(artifactBuild.getSpec().getGav());
                if (!deletedFinalStateUnknown) {
                    List<RebuiltArtifactDeletionListener> listeners = new ArrayList<>(imageDeletionListeners.size());
                    synchronized (imageDeletionListeners) {
                        listeners.addAll(imageDeletionListeners);
                    }
                    for (var i : listeners) {
                        try {
                            i.rebuiltArtifactDeleted(artifactBuild.getSpec().getGav(), artifactBuild.getSpec().getDigest());
                        } catch (Throwable t) {
                            Log.errorf(t, "Failed to notify deletion listener");
                        }
                    }
                }
            }
        });
    }

    public void addImageDeletionListener(RebuiltArtifactDeletionListener listener) {
        imageDeletionListeners.add(listener);
    }

    public boolean isPossiblyRebuilt(String gav) {
        return gavs.contains(gav);
    }

    public interface RebuiltArtifactDeletionListener {
        void rebuiltArtifactDeleted(String gav, String imageDigest);
    }
}
