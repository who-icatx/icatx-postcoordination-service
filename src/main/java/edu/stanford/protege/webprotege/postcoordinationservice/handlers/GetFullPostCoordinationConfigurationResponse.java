package edu.stanford.protege.webprotege.postcoordinationservice.handlers;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.stanford.protege.webprotege.common.Response;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationAxisConfiguration;

import java.util.List;


@JsonTypeName(GetFullPostCoordinationConfigurationRequest.CHANNEL)
public record GetFullPostCoordinationConfigurationResponse(List<PostCoordinationAxisConfiguration> configuration) implements Response {
}
