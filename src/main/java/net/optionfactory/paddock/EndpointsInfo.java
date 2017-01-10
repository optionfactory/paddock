package net.optionfactory.paddock;

import java.util.List;
import java.util.Map;

public class EndpointsInfo {

    public final List<EndpointInfo> endpoints;
    public final Map<String, DataTypeInfo> dataTypes;

    public EndpointsInfo(List<EndpointInfo> endpoints, Map<String, DataTypeInfo> dataTypes) {
        this.endpoints = endpoints;
        this.dataTypes = dataTypes;
    }

}
