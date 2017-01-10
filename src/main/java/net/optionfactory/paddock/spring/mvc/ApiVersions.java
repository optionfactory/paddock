package net.optionfactory.paddock.spring.mvc;

import java.util.Map;
import net.optionfactory.paddock.EndpointsInfo;

public class ApiVersions {

    public final Map<String, EndpointsInfo> versions;
    public String projectVersion;
    
    public ApiVersions(String projectVersion, Map<String, EndpointsInfo> versions) {
        this.projectVersion = projectVersion;
        this.versions = versions;
    }

}
