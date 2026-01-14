package edu.stanford.protege.webprotege.postcoordinationservice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;


@Document(collection = TableAxisLabel.AXIS_LABELS_COLLECTION)
public class TableAxisLabel {

    @Field("postcoordinationAxis")
    private final String postCoordinationAxis;

    @Field("tableLabel")
    private final String tableLabel;

    @Field("scaleLabel")
    private final String scaleLabel;

    @Field("postCoordinationAxisSortingCode")
    private final String postCoordinationAxisSortingCode;


    public final static String AXIS_LABELS_COLLECTION = "PostCoordinationTableAxisLabels";


    @JsonCreator
    public TableAxisLabel(@JsonProperty("postcoordinationAxis") String postCoordinationAxis,
                          @JsonProperty("tableLabel") String tableLabel,
                          @JsonProperty("scaleLabel") String scaleLabel,
                          @JsonProperty("postCoordinationAxisSortingCode") String postCoordinationAxisSortingCode) {
        this.postCoordinationAxis = postCoordinationAxis;
        this.tableLabel = tableLabel;
        this.scaleLabel = scaleLabel;
        this.postCoordinationAxisSortingCode = postCoordinationAxisSortingCode;
    }

    @JsonProperty("postcoordinationAxis")
    public String getPostCoordinationAxis() {
        return postCoordinationAxis;
    }

    @JsonProperty("tableLabel")
    public String getTableLabel() {
        return tableLabel;
    }

    @JsonProperty("scaleLabel")
    public String getScaleLabel() {
        return scaleLabel;
    }

    @JsonProperty("postCoordinationAxisSortingCode")
    public String getPostCoordinationAxisSortingCode() {
        return postCoordinationAxisSortingCode;
    }
}
