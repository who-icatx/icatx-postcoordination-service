package edu.stanford.protege.webprotege.postcoordinationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Set;

public class PostCoordinationAxisConfiguration {
    @JsonProperty("axisIdentifier")
    public String axisIdentifier;
    @JsonProperty("availableForEntityTypes")
    public Set<String> availableForEntityTypes;
    @JsonProperty("tableLabel")
    public String tableLabel;
    @JsonProperty("scaleLabel")
    public String scaleLabel;
    @JsonProperty("postCoordinationAxisSortingCode")
    public String postCoordinationAxisSortingCode;
    @JsonProperty("postCoordinationSubAxes")
    public Map<String, Set<String>> postCoordinationSubAxes;
    @JsonProperty("availableScalesTopClass")
    public String availableScalesTopClass;
    @JsonProperty("allowMultiValue")
    public String allowMultivalue;
}
