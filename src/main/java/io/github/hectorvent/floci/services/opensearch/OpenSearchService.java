package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.opensearch.container.OpenSearchContainerHandle;
import io.github.hectorvent.floci.services.opensearch.container.OpenSearchContainerManager;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchDomain;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchDomainStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core OpenSearch Service business logic — domain lifecycle management.
 * Creates OpenSearch Docker containers on domain creation, similar to ElastiCache/RDS.
 */
@ApplicationScoped
public class OpenSearchService {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private final StorageBackend<String, OpenSearchDomain> domains;
    private final OpenSearchContainerManager containerManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public OpenSearchService(OpenSearchContainerManager containerManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver) {
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.domains = new InMemoryStorage<>();
    }

    // Package-private constructor for testing
    OpenSearchService(StorageBackend<String, OpenSearchDomain> domains,
                      OpenSearchContainerManager containerManager,
                      EmulatorConfig config,
                      RegionResolver regionResolver) {
        this.domains = domains;
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    public OpenSearchDomain createDomain(String domainName, String engineVersion, String region) {
        String key = regionKey(region, domainName);
        if (domains.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain " + domainName + " already exists.", 409);
        }

        if (engineVersion == null || engineVersion.isBlank()) {
            engineVersion = config.services().opensearch().defaultEngineVersion();
        }

        String domainId = UUID.randomUUID().toString().substring(0, 8);
        String arn = regionResolver.buildArn("es", region, "domain/" + domainName);

        // Store domain immediately with CREATING status so retries see it exists
        OpenSearchDomain domain = new OpenSearchDomain(
                domainName, domainId, arn, engineVersion, null, Instant.now());
        domain.setStatus(OpenSearchDomainStatus.CREATING);
        domains.put(key, domain);
        LOG.infov("Creating OpenSearch domain {0} with engine={1}", domainName, engineVersion);

        // Start container asynchronously — update domain when ready
        String image = config.services().opensearch().defaultImage();
        Thread.ofVirtual().name("opensearch-start-" + domainName).start(() -> {
            try {
                OpenSearchContainerHandle handle = containerManager.start(domainName, image);
                String endpoint = handle.getHost() + ":" + handle.getPort();

                domain.setContainerId(handle.getContainerId());
                domain.setContainerHost(handle.getHost());
                domain.setContainerPort(handle.getPort());
                domain.setEndpoint(endpoint);
                domain.setStatus(OpenSearchDomainStatus.ACTIVE);
                domains.put(key, domain);
                LOG.infov("OpenSearch domain {0} ready, endpoint={1}", domainName, endpoint);
            } catch (Exception e) {
                LOG.errorv("Failed to start OpenSearch container for domain {0}: {1}",
                        domainName, e.getMessage());
                domains.delete(key);
            }
        });

        return domain;
    }

    public OpenSearchDomain getDomain(String domainName, String region) {
        String key = regionKey(region, domainName);
        return domains.get(key).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Domain " + domainName + " not found.", 404));
    }

    public Collection<OpenSearchDomain> listDomains(String region) {
        String prefix = region + "::";
        return domains.scan(k -> k.startsWith(prefix));
    }

    public void deleteDomain(String domainName, String region) {
        String key = regionKey(region, domainName);
        OpenSearchDomain domain = domains.get(key).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Domain " + domainName + " not found.", 404));

        domain.setStatus(OpenSearchDomainStatus.DELETING);
        domains.put(key, domain);

        if (domain.getContainerId() != null) {
            containerManager.stop(new OpenSearchContainerHandle(
                    domain.getContainerId(), domainName,
                    domain.getContainerHost(), domain.getContainerPort()));
        }

        domains.delete(key);
        LOG.infov("OpenSearch domain {0} deleted", domainName);
    }

    public void addTags(String arn, Map<String, String> tags, String region) {
        OpenSearchDomain domain = findByArn(arn, region);
        domain.getTags().putAll(tags);
        domains.put(regionKey(region, domain.getDomainName()), domain);
    }

    public void removeTags(String arn, List<String> tagKeys, String region) {
        OpenSearchDomain domain = findByArn(arn, region);
        tagKeys.forEach(domain.getTags()::remove);
        domains.put(regionKey(region, domain.getDomainName()), domain);
    }

    public Map<String, String> listTags(String arn, String region) {
        OpenSearchDomain domain = findByArn(arn, region);
        return domain.getTags();
    }

    private OpenSearchDomain findByArn(String arn, String region) {
        String prefix = region + "::";
        return domains.scan(k -> k.startsWith(prefix)).stream()
                .filter(d -> arn.equals(d.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found for ARN: " + arn, 404));
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }
}
