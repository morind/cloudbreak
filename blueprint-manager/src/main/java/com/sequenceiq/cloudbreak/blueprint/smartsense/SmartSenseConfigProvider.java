package com.sequenceiq.cloudbreak.blueprint.smartsense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sequenceiq.cloudbreak.api.model.InstanceGroupType;
import com.sequenceiq.cloudbreak.blueprint.BlueprintComponentConfigProvider;
import com.sequenceiq.cloudbreak.blueprint.BlueprintConfigurationEntry;
import com.sequenceiq.cloudbreak.blueprint.BlueprintPreparationObject;
import com.sequenceiq.cloudbreak.blueprint.BlueprintProcessor;
import com.sequenceiq.cloudbreak.blueprint.SmartsenseConfigurationLocator;
import com.sequenceiq.cloudbreak.domain.HostGroup;
import com.sequenceiq.cloudbreak.domain.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.json.Json;
import com.sequenceiq.cloudbreak.ha.CloudbreakNodeConfig;

@Component
public class SmartSenseConfigProvider implements BlueprintComponentConfigProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSenseConfigProvider.class);

    private static final String SMART_SENSE_SERVER_CONFIG_FILE = "hst-server-conf";

    private static final String HST_SERVER_COMPONENT = "HST_SERVER";

    private static final String HST_AGENT_COMPONENT = "HST_AGENT";

    private static final String RESOURCEMANAGER_COMPONENT = "RESOURCEMANAGER";

    private static final int SMART_SENSE_CLUSTER_NAME_MAX_LENGTH = 64;

    private static final String SMART_SENSE_PRODUCT_INFO_FILE = "product-info";

    @Value("${cb.product.id:cloudbreak}")
    private String productId;

    @Value("${cb.component.cluster.id:cloudbreak-hdp}")
    private String clustersComponentId;

    @Value("${info.app.version:}")
    private String cbVersion;

    @Inject
    private CloudbreakNodeConfig cloudbreakNodeConfig;

    @Inject
    private BlueprintProcessor blueprintProcessor;

    @Inject
    private SmartsenseConfigurationLocator smartsenseConfigurationLocator;

    @Override
    public String configure(BlueprintPreparationObject source, String blueprintText) {
        String smartSenseId = source.getSmartSenseSubscriptionId().get();
        Set<String> hostGroupNames = source.getHostGroups().stream().map(getHostGroupNameMapper()).collect(Collectors.toSet());
        blueprintText = addSmartSenseServerToBp(blueprintText, source.getHostGroups(), hostGroupNames);
        blueprintText = blueprintProcessor.addComponentToHostgroups(HST_AGENT_COMPONENT, hostGroupNames, blueprintText);
        List<BlueprintConfigurationEntry> configs = new ArrayList<>();
        configs.addAll(getSmartSenseServerConfigs(source.getStack(), smartSenseId));
        return blueprintProcessor.addConfigEntries(blueprintText, configs, true);
    }

    @Override
    public boolean additionalCriteria(BlueprintPreparationObject source, String blueprintText) {
        return smartsenseConfigurationLocator.smartsenseConfigurableBySubscriptionId(blueprintText, source.getSmartSenseSubscriptionId());
    }

    private Function<HostGroup, String> getHostGroupNameMapper() {
        return HostGroup::getName;
    }

    private String addSmartSenseServerToBp(String blueprintText, Iterable<HostGroup> hostGroups, Collection<String> hostGroupNames) {
        if (!blueprintProcessor.componentExistsInBlueprint(HST_SERVER_COMPONENT, blueprintText)) {
            String aHostGroupName = hostGroupNames.stream().findFirst().get();
            String finalBlueprintText = blueprintText;
            boolean singleNodeGatewayFound = false;
            for (HostGroup hostGroup : hostGroups) {
                InstanceGroup instanceGroup = hostGroup.getConstraint().getInstanceGroup();
                if (instanceGroup != null && InstanceGroupType.GATEWAY.equals(instanceGroup.getInstanceGroupType()) && instanceGroup.getNodeCount().equals(1)) {
                    aHostGroupName = hostGroup.getName();
                    singleNodeGatewayFound = true;
                    break;
                }
            }

            if (!singleNodeGatewayFound && blueprintProcessor.componentExistsInBlueprint(RESOURCEMANAGER_COMPONENT, blueprintText)) {
                Optional<String> hostGroupNameOfNameNode = hostGroupNames
                        .stream()
                        .filter(hGName -> blueprintProcessor.getComponentsInHostGroup(finalBlueprintText, hGName).contains(RESOURCEMANAGER_COMPONENT))
                        .findFirst();
                if (hostGroupNameOfNameNode.isPresent()) {
                    aHostGroupName = hostGroupNameOfNameNode.get();
                }

            }
            LOGGER.info("Adding '{}' component to '{}' hosgroup in the Blueprint.", HST_SERVER_COMPONENT, aHostGroupName);
            blueprintText = blueprintProcessor.addComponentToHostgroups(HST_SERVER_COMPONENT, Collections.singletonList(aHostGroupName), blueprintText);

        }
        return blueprintText;
    }

    private Collection<? extends BlueprintConfigurationEntry> getSmartSenseServerConfigs(Stack stack, String smartSenseId) {
        Collection<BlueprintConfigurationEntry> configs = new ArrayList<>();
        configs.add(new BlueprintConfigurationEntry(SMART_SENSE_SERVER_CONFIG_FILE, "customer.account.name", "Hortonworks_Cloud_HDP"));
        configs.add(new BlueprintConfigurationEntry(SMART_SENSE_SERVER_CONFIG_FILE, "customer.notification.email", "aws-marketplace@hortonworks.com"));
        String clusterName = getClusterName(stack);
        configs.add(new BlueprintConfigurationEntry(SMART_SENSE_SERVER_CONFIG_FILE, "cluster.name", clusterName));

        configs.add(new BlueprintConfigurationEntry(SMART_SENSE_SERVER_CONFIG_FILE, "customer.smartsense.id", smartSenseId));

        HSTMetadataInstanceInfoJson instanceInfoJson = new HSTMetadataInstanceInfoJson(
                stack.getFlexSubscription() != null ? stack.getFlexSubscription().getSubscriptionId() : "",
                clusterName,
                stack.getUuid(),
                cloudbreakNodeConfig.getInstanceUUID());
        HSTMetadataJson productInfo = new HSTMetadataJson(clustersComponentId, instanceInfoJson, productId, cbVersion);
        try {
            Json productInfoJson = new Json(productInfo);
            configs.add(new BlueprintConfigurationEntry(SMART_SENSE_PRODUCT_INFO_FILE, "product-info-content", productInfoJson.getValue()));
        } catch (JsonProcessingException ignored) {
            LOGGER.error("The 'product-info-content' SmartSense config could not be added to the Blueprint.");
        }
        return configs;
    }

    private String getClusterName(Stack stack) {
        String ssClusterNamePattern = "cbc--%s--%s";
        String clusterName = stack.getCluster().getName();
        String ssClusterName = String.format(ssClusterNamePattern, clusterName, stack.getUuid());
        if (ssClusterName.length() > SMART_SENSE_CLUSTER_NAME_MAX_LENGTH) {
            int charsOverTheLimit = ssClusterName.length() - SMART_SENSE_CLUSTER_NAME_MAX_LENGTH;
            ssClusterName = charsOverTheLimit < clusterName.length()
                    ? String.format(ssClusterNamePattern, clusterName.substring(0, clusterName.length() - charsOverTheLimit), stack.getUuid())
                    : ssClusterName.substring(0, SMART_SENSE_CLUSTER_NAME_MAX_LENGTH);
        }
        return ssClusterName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HSTMetadataInstanceInfoJson {
        private final String flexSubscriptionId;

        private final String guid;

        private final String name;

        private final String parentGuid;

        HSTMetadataInstanceInfoJson(String flexSubscriptionId, String guid, String name, String parentGuid) {
            this.flexSubscriptionId = flexSubscriptionId;
            this.guid = guid;
            this.name = name;
            this.parentGuid = parentGuid;
        }

        public String getFlexSubscriptionId() {
            return flexSubscriptionId;
        }

        public String getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }

        public String getParentGuid() {
            return parentGuid;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HSTMetadataJson {

        private static final String SCHEMA_VERSION = "1.0.0";

        private static final String TYPE = "cluster";

        private final String componentId;

        private final HSTMetadataInstanceInfoJson instanceInfo;

        private final String productId;

        private final String productVersion;

        HSTMetadataJson(String componentId, HSTMetadataInstanceInfoJson instanceInfo, String productId, String productVersion) {
            this.componentId = componentId;
            this.instanceInfo = instanceInfo;
            this.productId = productId;
            this.productVersion = productVersion;
        }

        public String getComponentId() {
            return componentId;
        }

        public HSTMetadataInstanceInfoJson getInstanceInfo() {
            return instanceInfo;
        }

        public String getProductId() {
            return productId;
        }

        public String getProductVersion() {
            return productVersion;
        }

        public String getSchemaVersion() {
            return SCHEMA_VERSION;
        }

        public String getType() {
            return TYPE;
        }
    }
}
