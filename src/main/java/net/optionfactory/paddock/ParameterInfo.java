package net.optionfactory.paddock;

public class ParameterInfo {

    public String type;
    public String name;
    public SendAs sendAs;
    public String description;
    public String[] help;

    public enum SendAs {

        PathVariable, RequestBody, RequestParameter, RequestHeader, MatrixVariable, Custom
    }


}
