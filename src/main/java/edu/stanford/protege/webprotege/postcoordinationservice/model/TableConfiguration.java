package edu.stanford.protege.webprotege.postcoordinationservice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

@Document(collection = TableConfiguration.DEFINITIONS_COLLECTION)
public class TableConfiguration {

    @Field("entityType")
    private final String entityType;
    @Field("postcoordinationAxes")
    private final List<String> postCoordinationAxes;

    @Field("compositePostcoordinationAxes")
    private final List<CompositeAxis> compositePostCoordinationAxes;

    public final static String DEFINITIONS_COLLECTION = "PostCoordinationTableConfiguration";

    @JsonCreator
    public TableConfiguration(@JsonProperty("entityType") String entityType, @JsonProperty("postcoordinationAxes") List<String> postCoordinationAxes, @JsonProperty("compositePostcoordinationAxes") List<CompositeAxis> compositePostCoordinationAxes) {
        this.entityType = entityType;
        this.postCoordinationAxes = postCoordinationAxes;
        this.compositePostCoordinationAxes = compositePostCoordinationAxes;
    }


    @JsonProperty("entityType")
    public String getEntityType() {
        return entityType;
    }

    @JsonProperty("postcoordinationAxes")
    public List<String> getPostCoordinationAxes() {
        return postCoordinationAxes;
    }

    @JsonProperty("compositePostcoordinationAxes")
    public List<CompositeAxis> getCompositePostCoordinationAxes() {
        return compositePostCoordinationAxes;
    }
}
