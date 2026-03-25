package io.github.hectorvent.floci.services.opensearch.container;

import java.io.Closeable;

/**
 * Wraps a running backend Docker container for an OpenSearch domain.
 */
public class OpenSearchContainerHandle {

    private final String containerId;
    private final String domainName;
    private final String host;
    private final int port;
    private Closeable logStream;

    public OpenSearchContainerHandle(String containerId, String domainName, String host, int port) {
        this.containerId = containerId;
        this.domainName = domainName;
        this.host = host;
        this.port = port;
    }

    public String getContainerId() { return containerId; }
    public String getDomainName() { return domainName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
