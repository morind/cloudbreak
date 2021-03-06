package com.sequenceiq.cloudbreak.blueprint.validation;

import java.util.Collection;
import java.util.List;

import com.google.common.collect.Lists;
import com.sequenceiq.cloudbreak.domain.HostGroup;

public class BlueprintServiceComponent {

    private final String name;

    private int nodeCount;

    private final List<String> hostgroups;

    BlueprintServiceComponent(String name, String hostgroup, int nodeCount) {
        this.name = name;
        this.nodeCount = nodeCount;
        hostgroups = Lists.newArrayList(hostgroup);
    }

    public void update(HostGroup hostGroup) {
        nodeCount += hostGroup.getConstraint().getHostCount();
        hostgroups.add(hostGroup.getName());
    }

    public String getName() {
        return name;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public Collection<String> getHostgroups() {
        return hostgroups;
    }
}
