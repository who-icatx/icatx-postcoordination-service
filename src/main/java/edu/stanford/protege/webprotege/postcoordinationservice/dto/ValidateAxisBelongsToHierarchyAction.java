package edu.stanford.protege.webprotege.postcoordinationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.stanford.protege.webprotege.common.ProjectId;
import edu.stanford.protege.webprotege.dispatch.ProjectAction;
import org.jetbrains.annotations.NotNull;
import org.semanticweb.owlapi.model.IRI;

import java.util.List;
import java.util.Map;

@JsonTypeName(ValidateAxisBelongsToHierarchyAction.CHANNEL)
public record ValidateAxisBelongsToHierarchyAction(
        @JsonProperty("projectId") @NotNull ProjectId projectId,
        @JsonProperty("hierarchyRootsToEntities") @NotNull Map<IRI, List<IRI>> hierarchyRootsToEntities
) implements ProjectAction<ValidateAxisBelongsToHierarchyResult> {
    
    public static final String CHANNEL = "webprotege.icd.ValidateAxisBelongsToHierarchy";

    @Override
    public String getChannel() {
        return CHANNEL;
    }
}
