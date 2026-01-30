package edu.stanford.protege.webprotege.postcoordinationservice.handlers;

import com.fasterxml.jackson.annotation.*;
import edu.stanford.protege.webprotege.common.*;
import edu.stanford.protege.webprotege.postcoordinationservice.model.WhoficCustomScalesValues;
import edu.stanford.protege.webprotege.postcoordinationservice.model.WhoficEntityPostCoordinationSpecification;

import static edu.stanford.protege.webprotege.postcoordinationservice.handlers.ValidateEntityUpdateRequest.CHANNEL;

@JsonTypeName(CHANNEL)
public record ValidateEntityUpdateRequest(@JsonProperty("projectId")
                                         ProjectId projectId,
                                         @JsonProperty("entityCustomScaleValues")
                                         WhoficCustomScalesValues entityCustomScaleValues,
                                         @JsonProperty("entitySpecification")
                                         WhoficEntityPostCoordinationSpecification entitySpecification) implements Request<ValidateEntityUpdateResponse> {

    public final static String CHANNEL = "webprotege.postcoordination.ValidateEntityUpdate";

    @Override
    public String getChannel() {
        return CHANNEL;
    }
}
