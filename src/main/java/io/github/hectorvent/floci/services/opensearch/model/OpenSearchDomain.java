package io.github.hectorvent.floci.services.opensearch.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class OpenSearchDomain {

    private String domainName;
    private String domainId;
    private String arn;
    private String engineVersion;
    private OpenSearchDomainStatus status;
    private String endpoint;
    private Instant created;
    private Map<String, String> tags;

    // Transient fields — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public OpenSearchDomain() {}

    public OpenSearchDomain(String domainName, String domainId, String arn,
                            String engineVersion, String endpoint, Instant created) {
        this.domainName = domainName;
        this.domainId = domainId;
        this.arn = arn;
        this.engineVersion = engineVersion;
        this.status = OpenSearchDomainStatus.ACTIVE;
        this.endpoint = endpoint;
        this.created = created;
        this.tags = new HashMap<>();
    }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public OpenSearchDomainStatus getStatus() { return status; }
    public void setStatus(OpenSearchDomainStatus status) { this.status = status; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
