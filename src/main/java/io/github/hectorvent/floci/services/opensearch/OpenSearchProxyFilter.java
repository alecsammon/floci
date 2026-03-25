package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchDomain;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-matching filter that intercepts requests to /opensearch/{region}/{domainName}/...
 * and proxies them to the backing OpenSearch Docker container.
 * This must run before JAX-RS routing to prevent S3 from catching these paths.
 */
@Provider
@PreMatching
@Priority(4) // Run before other filters
public class OpenSearchProxyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(OpenSearchProxyFilter.class);
    private static final Pattern OPENSEARCH_PATH = Pattern.compile(
            "^/opensearch/([^/]+)/([^/]+)(?:/(.*))?$");

    private final OpenSearchService openSearchService;
    private final HttpClient httpClient;

    @Inject
    public OpenSearchProxyFilter(OpenSearchService openSearchService) {
        this.openSearchService = openSearchService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getRequestUri().getRawPath();
        Matcher m = OPENSEARCH_PATH.matcher(path);
        if (!m.matches()) {
            return;
        }

        String region = m.group(1);
        String domainName = m.group(2);
        String remainingPath = m.group(3) != null ? m.group(3) : "";

        OpenSearchDomain domain;
        try {
            domain = openSearchService.getDomain(domainName, region);
        } catch (AwsException e) {
            ctx.abortWith(Response.status(404)
                    .entity("{\"error\":\"Domain " + domainName + " not found\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        String endpoint = domain.getEndpoint();
        if (endpoint == null) {
            ctx.abortWith(Response.status(503)
                    .entity("{\"error\":\"Domain " + domainName + " is not ready\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        String method = ctx.getMethod();
        String targetUrl = "http://" + endpoint + "/" + remainingPath;
        LOG.debugv("OpenSearch proxy: {0} {1} -> {2}", method, path, targetUrl);

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(30));

            // Read request body for methods that may have one
            String body = null;
            if (ctx.hasEntity()) {
                body = new String(ctx.getEntityStream().readAllBytes());
            }

            if (body != null && !body.isEmpty()) {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            reqBuilder.header("Content-Type", "application/json");

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String contentType = resp.headers().firstValue("content-type")
                    .orElse("application/json");
            ctx.abortWith(Response.status(resp.statusCode())
                    .entity(resp.body())
                    .type(contentType)
                    .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.abortWith(Response.status(502)
                    .entity("{\"error\":\"Proxy interrupted\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        } catch (IOException e) {
            LOG.warnv("OpenSearch proxy error for domain {0}: {1}", domainName, e.getMessage());
            ctx.abortWith(Response.status(502)
                    .entity("{\"error\":\"Failed to connect to OpenSearch backend: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }
}
