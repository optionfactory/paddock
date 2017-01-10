package net.optionfactory.paddock;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

public class DataTypeInfo {

    @JsonIgnore
    public String type;
    public String description;
    public String[] help;
    public Map<String, FieldInfo> fields;

}
