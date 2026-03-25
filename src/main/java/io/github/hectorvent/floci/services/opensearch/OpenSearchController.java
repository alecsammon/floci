package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchDomain;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchDomainStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * AWS OpenSearch Service REST API endpoints.
 * All endpoints are under /2021-01-01/opensearch matching the AWS OpenSearch Service API version.
 */
@Path("/2021-01-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenSearchController {

    private static final Logger LOG = Logger.getLogger(OpenSearchController.class);

    private final OpenSearchService openSearchService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    @Inject
    public OpenSearchController(OpenSearchService openSearchService,
                                RegionResolver regionResolver,
                                ObjectMapper objectMapper,
                                EmulatorConfig config) {
        this.openSearchService = openSearchService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ── CreateDomain ──────────────────────────────────────────────────────────

    @POST
    @Path("/opensearch/domain")
    public Response createDomain(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String domainName = request.path("DomainName").asText(null);
            if (domainName == null || domainName.isBlank()) {
                throw new AwsException("ValidationException", "DomainName is required.", 400);
            }

            String engineVersion = request.path("EngineVersion").asText(null);

            OpenSearchDomain domain = openSearchService.createDomain(domainName, engineVersion, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainStatus", buildDomainStatus(domain, true));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        } catch (Exception e) {
            return errorResponse(new AwsException("InternalException", e.getMessage(), 500));
        }
    }

    // ── DeleteDomain ──────────────────────────────────────────────────────────

    @DELETE
    @Path("/opensearch/domain/{domainName}")
    public Response deleteDomain(@Context HttpHeaders headers,
                                 @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OpenSearchDomain domain = openSearchService.getDomain(domainName, region);
            ObjectNode status = buildDomainStatus(domain, true);
            openSearchService.deleteDomain(domainName, region);

            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainStatus", status);
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── DescribeDomain ────────────────────────────────────────────────────────

    @GET
    @Path("/opensearch/domain/{domainName}")
    public Response describeDomain(@Context HttpHeaders headers,
                                   @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OpenSearchDomain domain = openSearchService.getDomain(domainName, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainStatus", buildDomainStatus(domain, true));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── DescribeDomains (batch) ───────────────────────────────────────────────

    @POST
    @Path("/opensearch/domain-info")
    public Response describeDomains(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode domainNames = request.path("DomainNames");

            ArrayNode statusList = objectMapper.createArrayNode();
            if (domainNames.isArray()) {
                for (JsonNode nameNode : domainNames) {
                    try {
                        OpenSearchDomain domain = openSearchService.getDomain(nameNode.asText(), region);
                        statusList.add(buildDomainStatus(domain, true));
                    } catch (AwsException ignored) {
                        // skip domains that don't exist
                    }
                }
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainStatusList", statusList);
            return Response.ok(response).build();
        } catch (Exception e) {
            return errorResponse(new AwsException("InternalException", e.getMessage(), 500));
        }
    }

    // ── ListDomainNames ───────────────────────────────────────────────────────

    @GET
    @Path("/domain")
    public Response listDomainNames(@Context HttpHeaders headers,
                                    @QueryParam("engineType") String engineType) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Collection<OpenSearchDomain> domains = openSearchService.listDomains(region);
            ArrayNode domainNames = objectMapper.createArrayNode();
            for (OpenSearchDomain domain : domains) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("DomainName", domain.getDomainName());
                entry.put("EngineType", "OpenSearch");
                domainNames.add(entry);
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainNames", domainNames);
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── DescribeDomainConfig ──────────────────────────────────────────────────

    @GET
    @Path("/opensearch/domain/{domainName}/config")
    public Response describeDomainConfig(@Context HttpHeaders headers,
                                         @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OpenSearchDomain domain = openSearchService.getDomain(domainName, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainConfig", buildDomainConfig(domain));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── AddTags ───────────────────────────────────────────────────────────────

    @POST
    @Path("/tags")
    public Response addTags(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String arn = request.path("ARN").asText(null);
            if (arn == null) {
                throw new AwsException("ValidationException", "ARN is required.", 400);
            }

            Map<String, String> tags = new HashMap<>();
            JsonNode tagList = request.path("TagList");
            if (tagList.isArray()) {
                for (JsonNode tag : tagList) {
                    tags.put(tag.path("Key").asText(), tag.path("Value").asText(""));
                }
            }

            openSearchService.addTags(arn, tags, region);
            return Response.ok().build();
        } catch (AwsException e) {
            return errorResponse(e);
        } catch (Exception e) {
            return errorResponse(new AwsException("InternalException", e.getMessage(), 500));
        }
    }

    // ── RemoveTags ────────────────────────────────────────────────────────────

    @POST
    @Path("/tags-removal")
    public Response removeTags(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String arn = request.path("ARN").asText(null);
            if (arn == null) {
                throw new AwsException("ValidationException", "ARN is required.", 400);
            }

            JsonNode tagKeys = request.path("TagKeys");
            List<String> keys = StreamSupport.stream(tagKeys.spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();

            openSearchService.removeTags(arn, keys, region);
            return Response.ok().build();
        } catch (AwsException e) {
            return errorResponse(e);
        } catch (Exception e) {
            return errorResponse(new AwsException("InternalException", e.getMessage(), 500));
        }
    }

    // ── ListTags ──────────────────────────────────────────────────────────────

    @GET
    @Path("/tags")
    public Response listTags(@Context HttpHeaders headers,
                             @QueryParam("arn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (arn == null || arn.isBlank()) {
                throw new AwsException("ValidationException", "ARN is required.", 400);
            }

            Map<String, String> tags = openSearchService.listTags(arn, region);
            ArrayNode tagList = objectMapper.createArrayNode();
            tags.forEach((key, value) -> {
                ObjectNode tag = objectMapper.createObjectNode();
                tag.put("Key", key);
                tag.put("Value", value);
                tagList.add(tag);
            });

            ObjectNode response = objectMapper.createObjectNode();
            response.set("TagList", tagList);
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── DescribeDomainChangeProgress ──────────────────────────────────────────

    @GET
    @Path("/opensearch/domain/{domainName}/progress")
    public Response describeDomainChangeProgress(@Context HttpHeaders headers,
                                                  @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OpenSearchDomain domain = openSearchService.getDomain(domainName, region);
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode changeProgressStatus = objectMapper.createObjectNode();

            boolean completed = domain.getStatus() == OpenSearchDomainStatus.ACTIVE;
            changeProgressStatus.put("Status", completed ? "COMPLETED" : "PENDING");
            changeProgressStatus.put("TotalNumberOfStages", 1);
            changeProgressStatus.put("CompletedNumberOfStages", completed ? 1 : 0);

            ArrayNode stages = objectMapper.createArrayNode();
            ObjectNode stage = objectMapper.createObjectNode();
            stage.put("Name", "Update");
            stage.put("Status", completed ? "COMPLETED" : "PENDING");
            stages.add(stage);
            changeProgressStatus.set("ChangeProgressStages", stages);

            response.set("ChangeProgressStatus", changeProgressStatus);
            return Response.ok(response).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    // ── GetCompatibleVersions ────────────────────────────────────────────────

    @GET
    @Path("/opensearch/compatibleVersions")
    public Response getCompatibleVersions(@Context HttpHeaders headers,
                                          @QueryParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode compatibleVersions = objectMapper.createArrayNode();

        // Return a single entry matching the default engine version
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("SourceVersion", config.services().opensearch().defaultEngineVersion());

        ArrayNode targetVersions = objectMapper.createArrayNode();
        targetVersions.add(config.services().opensearch().defaultEngineVersion());
        entry.set("TargetVersions", targetVersions);

        compatibleVersions.add(entry);
        response.set("CompatibleVersions", compatibleVersions);
        return Response.ok(response).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode buildDomainStatus(OpenSearchDomain domain, boolean created) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("DomainId", regionResolver.getAccountId() + "/" + domain.getDomainName());
        status.put("DomainName", domain.getDomainName());
        status.put("ARN", domain.getArn());
        status.put("Created", created);
        status.put("Deleted", domain.getStatus() == OpenSearchDomainStatus.DELETING);
        status.put("Processing", domain.getStatus() == OpenSearchDomainStatus.CREATING);
        status.put("EngineVersion", domain.getEngineVersion());
        if (domain.getEndpoint() != null) {
            status.put("Endpoint", domain.getEndpoint());
        }

        ObjectNode clusterConfig = objectMapper.createObjectNode();
        clusterConfig.put("InstanceType", "m5.large.search");
        clusterConfig.put("InstanceCount", 1);
        clusterConfig.put("DedicatedMasterEnabled", false);
        clusterConfig.put("ZoneAwarenessEnabled", false);
        status.set("ClusterConfig", clusterConfig);

        ObjectNode ebsOptions = objectMapper.createObjectNode();
        ebsOptions.put("EBSEnabled", true);
        ebsOptions.put("VolumeType", "gp3");
        ebsOptions.put("VolumeSize", 10);
        status.set("EBSOptions", ebsOptions);

        ObjectNode encryptionAtRest = objectMapper.createObjectNode();
        encryptionAtRest.put("Enabled", false);
        status.set("EncryptionAtRestOptions", encryptionAtRest);

        ObjectNode nodeToNode = objectMapper.createObjectNode();
        nodeToNode.put("Enabled", false);
        status.set("NodeToNodeEncryptionOptions", nodeToNode);

        ObjectNode advancedSecurity = objectMapper.createObjectNode();
        advancedSecurity.put("Enabled", false);
        advancedSecurity.put("InternalUserDatabaseEnabled", false);
        status.set("AdvancedSecurityOptions", advancedSecurity);

        ObjectNode autoTune = objectMapper.createObjectNode();
        autoTune.put("State", "ENABLED");
        status.set("AutoTuneOptions", autoTune);

        ObjectNode domainEndpointOptions = objectMapper.createObjectNode();
        domainEndpointOptions.put("EnforceHTTPS", false);
        domainEndpointOptions.put("TLSSecurityPolicy", "Policy-Min-TLS-1-0-2019-07");
        domainEndpointOptions.put("CustomEndpointEnabled", false);
        status.set("DomainEndpointOptions", domainEndpointOptions);

        ObjectNode softwareUpdateOptions = objectMapper.createObjectNode();
        softwareUpdateOptions.put("AutoSoftwareUpdateEnabled", false);
        status.set("SoftwareUpdateOptions", softwareUpdateOptions);

        ObjectNode offPeakWindowOptions = objectMapper.createObjectNode();
        offPeakWindowOptions.put("Enabled", false);
        status.set("OffPeakWindowOptions", offPeakWindowOptions);

        status.putObject("AdvancedOptions");
        status.putObject("LogPublishingOptions");
        status.putObject("VPCOptions");
        status.putObject("CognitoOptions").put("Enabled", false);
        status.putObject("SnapshotOptions").put("AutomatedSnapshotStartHour", 0);
        status.put("AccessPolicies", "");
        status.put("UpgradeProcessing", false);

        return status;
    }

    private ObjectNode buildDomainConfig(OpenSearchDomain domain) {
        ObjectNode config = objectMapper.createObjectNode();

        ObjectNode engineOpts = objectMapper.createObjectNode();
        engineOpts.put("Options", domain.getEngineVersion());
        ObjectNode engineStatus = objectMapper.createObjectNode();
        engineStatus.put("State", "Active");
        engineOpts.set("Status", engineStatus);
        config.set("EngineVersion", engineOpts);

        ObjectNode clusterConfig = objectMapper.createObjectNode();
        ObjectNode clusterOpts = objectMapper.createObjectNode();
        clusterOpts.put("InstanceType", "m5.large.search");
        clusterOpts.put("InstanceCount", 1);
        clusterConfig.set("Options", clusterOpts);
        ObjectNode clusterStatus = objectMapper.createObjectNode();
        clusterStatus.put("State", "Active");
        clusterConfig.set("Status", clusterStatus);
        config.set("ClusterConfig", clusterConfig);

        return config;
    }

    private Response errorResponse(AwsException e) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", e.getMessage());
        return Response.status(e.getHttpStatus()).entity(error).build();
    }
}
