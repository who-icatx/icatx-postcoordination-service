package edu.stanford.protege.webprotege.postcoordinationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.stanford.protege.webprotege.dispatch.Result;
import org.jetbrains.annotations.NotNull;
import org.semanticweb.owlapi.model.IRI;

import java.util.List;
import java.util.Map;

@JsonTypeName(ValidateAxisBelongsToHierarchyAction.CHANNEL)
public record ValidateAxisBelongsToHierarchyResult(
        @JsonProperty("invalidEntitiesByRoot") @NotNull Map<IRI, List<IRI>> invalidEntitiesByRoot
) implements Result {
    
    public static ValidateAxisBelongsToHierarchyResult create(@NotNull Map<IRI, List<IRI>> invalidEntitiesByRoot) {
        return new ValidateAxisBelongsToHierarchyResult(invalidEntitiesByRoot);
    }
}
