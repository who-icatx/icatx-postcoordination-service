package edu.stanford.protege.webprotege.postcoordinationservice.handlers;


import edu.stanford.protege.webprotege.ipc.CommandHandler;
import edu.stanford.protege.webprotege.ipc.ExecutionContext;
import edu.stanford.protege.webprotege.ipc.WebProtegeHandler;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationAxisConfiguration;
import edu.stanford.protege.webprotege.postcoordinationservice.model.CompositeAxis;
import edu.stanford.protege.webprotege.postcoordinationservice.model.PostcoordinationAxisToGenericScale;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableAxisLabel;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableConfiguration;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationTableConfigRepository;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostcoordinationAxisToGenericScaleRepository;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;

@WebProtegeHandler
public class GetFullPostCoordinationConfigurationCommandHandler implements CommandHandler<GetFullPostCoordinationConfigurationRequest, GetFullPostCoordinationConfigurationResponse> {

    private final static Logger log = LoggerFactory.getLogger(GetFullPostCoordinationConfigurationCommandHandler.class);
    private final PostCoordinationTableConfigRepository configRepository;

    private final PostcoordinationAxisToGenericScaleRepository axisToGenericScale;

    public GetFullPostCoordinationConfigurationCommandHandler(PostCoordinationTableConfigRepository configRepository, PostcoordinationAxisToGenericScaleRepository axisToGenericScale) {
        this.configRepository = configRepository;
        this.axisToGenericScale = axisToGenericScale;
    }

    @Override
    public @NotNull String getChannelName() {
        return GetFullPostCoordinationConfigurationRequest.CHANNEL;
    }

    @Override
    public Class<GetFullPostCoordinationConfigurationRequest> getRequestClass() {
        return GetFullPostCoordinationConfigurationRequest.class;
    }

    @Override
    public Mono<GetFullPostCoordinationConfigurationResponse> handleRequest(GetFullPostCoordinationConfigurationRequest request, ExecutionContext executionContext) {

        List<TableAxisLabel> axisLabelList = configRepository.getTableAxisLabels();
        List<TableConfiguration> tableConfigurations = configRepository.getALlTableConfiguration();
        List<PostcoordinationAxisToGenericScale> axisToGenericScales = axisToGenericScale.getPostCoordAxisToGenericScale();
        List<PostCoordinationAxisConfiguration> response = new ArrayList<>();
        for(TableAxisLabel axisLabel : axisLabelList) {
           PostCoordinationAxisConfiguration postCoordinationAxisConfiguration = new PostCoordinationAxisConfiguration();
           postCoordinationAxisConfiguration.axisIdentifier = axisLabel.getPostCoordinationAxis();
           postCoordinationAxisConfiguration.tableLabel = axisLabel.getTableLabel();
           postCoordinationAxisConfiguration.scaleLabel = axisLabel.getScaleLabel();
           postCoordinationAxisConfiguration.postCoordinationAxisSortingCode = axisLabel.getPostCoordinationAxisSortingCode();
           populateFromAxisToGenericScale(postCoordinationAxisConfiguration, axisToGenericScales);
           populateFromTableConfiguration(postCoordinationAxisConfiguration, tableConfigurations);
           response.add(postCoordinationAxisConfiguration);
        }

        return Mono.just(new GetFullPostCoordinationConfigurationResponse(response));
    }


    private void populateFromAxisToGenericScale(PostCoordinationAxisConfiguration postCoordinationAxisConfiguration, List<PostcoordinationAxisToGenericScale> axisToGenericScales) {
        Optional<PostcoordinationAxisToGenericScale> scale = axisToGenericScales.stream()
                .filter(axisToGenericScale -> axisToGenericScale.getPostcoordinationAxis().equals(postCoordinationAxisConfiguration.axisIdentifier))
                .findAny();
        if(scale.isPresent()) {
            postCoordinationAxisConfiguration.availableScalesTopClass = scale.get().getGenericPostcoordinationScaleTopClass();
            postCoordinationAxisConfiguration.allowMultivalue = scale.get().getAllowMultiValue();
        } else {
            log.warn("Couldn't find axis to generic scale for " + postCoordinationAxisConfiguration.axisIdentifier);
        }
    }


    private void populateFromTableConfiguration(PostCoordinationAxisConfiguration postCoordinationAxisConfiguration, List<TableConfiguration> tableConfigurations) {
        postCoordinationAxisConfiguration.availableForEntityTypes = new HashSet<>();
        postCoordinationAxisConfiguration.postCoordinationSubAxes = new HashMap<>();
        for(TableConfiguration tableConfiguration : tableConfigurations) {
            if(tableConfiguration.getPostCoordinationAxes().contains(postCoordinationAxisConfiguration.axisIdentifier)) {
                postCoordinationAxisConfiguration.availableForEntityTypes.add(tableConfiguration.getEntityType());
            }
            for(CompositeAxis compositeAxis : tableConfiguration.getCompositePostCoordinationAxes()) {
                if(compositeAxis.getPostCoordinationAxis().equals(postCoordinationAxisConfiguration.axisIdentifier)) {
                    Set<String> availableSubAxes = postCoordinationAxisConfiguration.postCoordinationSubAxes.get(tableConfiguration.getEntityType());
                    if(availableSubAxes == null) {
                        availableSubAxes = new HashSet<>();
                    }
                    availableSubAxes.addAll(compositeAxis.getSubAxis());
                    postCoordinationAxisConfiguration.postCoordinationSubAxes.put(tableConfiguration.getEntityType(), availableSubAxes);
                }
            }
        }
    }
}
