package io.github.hectorvent.floci.services.opensearch.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.lambda.launcher.DockerHostResolver;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages backend Docker container lifecycle for OpenSearch domains.
 * Starts opensearchproject/opensearch containers with single-node discovery.
 */
@ApplicationScoped
public class OpenSearchContainerManager {

    private static final Logger LOG = Logger.getLogger(OpenSearchContainerManager.class);
    private static final int BACKEND_PORT = 9200;
    private static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final CloudWatchLogsService cloudWatchLogsService;
    private final RegionResolver regionResolver;

    @Inject
    public OpenSearchContainerManager(DockerClient dockerClient,
                                      ImageCacheService imageCacheService,
                                      DockerHostResolver dockerHostResolver,
                                      EmulatorConfig config,
                                      CloudWatchLogsService cloudWatchLogsService,
                                      RegionResolver regionResolver) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.cloudWatchLogsService = cloudWatchLogsService;
        this.regionResolver = regionResolver;
    }

    public OpenSearchContainerHandle start(String domainName, String image) {
        LOG.infov("Starting OpenSearch backend container for domain: {0}", domainName);
        imageCacheService.ensureImageExists(image);

        boolean nativeMode = HOST_DOCKER_INTERNAL.equals(dockerHostResolver.resolve());

        HostConfig hostConfig = buildHostConfig(nativeMode);
        String containerName = "floci-opensearch-" + domainName;

        // Remove any stale container with the same name from a previous run
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            LOG.infov("Removed stale OpenSearch container: {0}", containerName);
        } catch (Exception ignored) {
            // Container doesn't exist — expected on first run
        }

        config.services().opensearch().dockerNetwork()
                .or(() -> config.services().dockerNetwork())
                .ifPresent(network -> {
                    if (!network.isBlank()) {
                        hostConfig.withNetworkMode(network);
                        LOG.debugv("Attaching OpenSearch container to network: {0}", network);
                    }
                });

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withEnv(List.of(
                        "discovery.type=single-node",
                        "DISABLE_SECURITY_PLUGIN=true",
                        "OPENSEARCH_INITIAL_ADMIN_PASSWORD=FlociLocal123!",
                        "OPENSEARCH_JAVA_OPTS=-Xms256m -Xmx256m"
                ))
                .withExposedPorts(ExposedPort.tcp(BACKEND_PORT))
                .withHostConfig(hostConfig)
                .exec();

        String containerId = container.getId();
        LOG.infov("Created OpenSearch container {0} for domain {1}", containerId, domainName);

        dockerClient.startContainerCmd(containerId).exec();
        LOG.infov("Started OpenSearch container {0}", containerId);

        String backendHost;
        int backendPort;

        if (nativeMode) {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(BACKEND_PORT));
            if (binding != null && binding.length > 0) {
                backendPort = Integer.parseInt(binding[0].getHostPortSpec());
            } else {
                backendPort = BACKEND_PORT;
            }
            backendHost = "localhost";
        } else {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            var networks = inspect.getNetworkSettings().getNetworks();
            String containerIp = null;
            if (networks != null) {
                for (Map.Entry<String, ?> entry : networks.entrySet()) {
                    var netEntry = (com.github.dockerjava.api.model.ContainerNetwork) entry.getValue();
                    containerIp = netEntry.getIpAddress();
                    if (containerIp != null && !containerIp.isBlank()) {
                        break;
                    }
                }
            }
            if (containerIp == null || containerIp.isBlank()) {
                containerIp = inspect.getNetworkSettings().getIpAddress();
            }
            backendHost = containerIp;
            backendPort = BACKEND_PORT;
        }

        LOG.infov("OpenSearch backend for domain {0}: {1}:{2}", domainName, backendHost, backendPort);

        OpenSearchContainerHandle handle = new OpenSearchContainerHandle(
                containerId, domainName, backendHost, backendPort);

        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        attachLogStream(handle, domainName, containerId, shortId);

        return handle;
    }

    public void stop(OpenSearchContainerHandle handle) {
        if (handle == null) {
            return;
        }
        LOG.infov("Stopping OpenSearch container {0}", handle.getContainerId());

        if (handle.getLogStream() != null) {
            try { handle.getLogStream().close(); } catch (Exception ignored) {}
        }

        try {
            dockerClient.stopContainerCmd(handle.getContainerId()).withTimeout(10).exec();
        } catch (Exception e) {
            LOG.warnv("Error stopping OpenSearch container {0}: {1}",
                    handle.getContainerId(), e.getMessage());
        }

        try {
            dockerClient.removeContainerCmd(handle.getContainerId()).withForce(true).exec();
        } catch (Exception e) {
            LOG.warnv("Error removing OpenSearch container {0}: {1}",
                    handle.getContainerId(), e.getMessage());
        }
    }

    private HostConfig buildHostConfig(boolean nativeMode) {
        HostConfig hostConfig = HostConfig.newHostConfig();
        if (nativeMode) {
            int freePort = findFreePort();
            Ports portBindings = new Ports();
            portBindings.bind(ExposedPort.tcp(BACKEND_PORT), Ports.Binding.bindPort(freePort));
            hostConfig.withPortBindings(portBindings);
            LOG.debugv("Native mode: binding container port 9200 -> host port {0}", freePort);
        }
        return hostConfig;
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port for OpenSearch container", e);
        }
    }

    private void attachLogStream(OpenSearchContainerHandle handle, String domainName,
                                  String containerId, String shortId) {
        String logGroup = "/aws/opensearch/domains/" + domainName + "/application-logs";
        String region = regionResolver.getDefaultRegion();
        String logStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/" + shortId;
        ensureLogGroupAndStream(logGroup, logStream, region);

        try {
            ResultCallback.Adapter<Frame> logCallback = dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                            if (!line.isEmpty()) {
                                LOG.infov("[opensearch:{0}] {1}", domainName, line);
                                forwardToCloudWatchLogs(logGroup, logStream, region, line);
                            }
                        }
                    });
            handle.setLogStream(logCallback);
        } catch (Exception e) {
            LOG.warnv("Could not attach log stream for OpenSearch container {0}: {1}",
                    containerId, e.getMessage());
        }
    }

    private void ensureLogGroupAndStream(String logGroup, String logStream, String region) {
        try {
            cloudWatchLogsService.createLogGroup(logGroup, null, null, region);
        } catch (AwsException ignored) {
        } catch (Exception e) {
            LOG.warnv("Could not create CW log group {0}: {1}", logGroup, e.getMessage());
        }
        try {
            cloudWatchLogsService.createLogStream(logGroup, logStream, region);
        } catch (AwsException ignored) {
        } catch (Exception e) {
            LOG.warnv("Could not create CW log stream {0}/{1}: {2}", logGroup, logStream, e.getMessage());
        }
    }

    private void forwardToCloudWatchLogs(String logGroup, String logStream, String region, String line) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("message", line);
            cloudWatchLogsService.putLogEvents(logGroup, logStream, List.of(event), region);
        } catch (Exception e) {
            LOG.debugv("Could not forward OpenSearch log line to CloudWatch Logs: {0}", e.getMessage());
        }
    }
}
