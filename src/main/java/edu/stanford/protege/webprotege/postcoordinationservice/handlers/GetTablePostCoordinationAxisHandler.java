package edu.stanford.protege.webprotege.postcoordinationservice.handlers;

import edu.stanford.protege.webprotege.ipc.*;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.model.CompositeAxis;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableAxisLabel;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableConfiguration;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationTableConfigRepository;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


@WebProtegeHandler
public class GetTablePostCoordinationAxisHandler implements CommandHandler<GetTablePostCoordinationAxisRequest, GetTablePostCoordinationAxisResponse> {

    private final static Logger LOGGER = LoggerFactory.getLogger(GetTablePostCoordinationAxisRequest.class);

    private final PostCoordinationTableConfigRepository tableConfigRepository;
    private final CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypesExecutor;
    public GetTablePostCoordinationAxisHandler(PostCoordinationTableConfigRepository tableConfigRepository,
                                               CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypesExecutor) {
        this.tableConfigRepository = tableConfigRepository;
        this.entityTypesExecutor = entityTypesExecutor;
    }


    @NotNull
    @Override
    public String getChannelName() {
        return GetTablePostCoordinationAxisRequest.CHANNEL;
    }

    @Override
    public Class<GetTablePostCoordinationAxisRequest> getRequestClass() {
        return GetTablePostCoordinationAxisRequest.class;
    }

    @Override
    public Mono<GetTablePostCoordinationAxisResponse> handleRequest(GetTablePostCoordinationAxisRequest request, ExecutionContext executionContext) {
        try {
            GetIcatxEntityTypeResponse response = entityTypesExecutor.execute(new GetIcatxEntityTypeRequest(request.entityIri(), request.projectId()), executionContext)
                    .get(15, TimeUnit.SECONDS);
            List<TableConfiguration> tableConfigurations = tableConfigRepository.getTableConfigurationByEntityType(response.icatxEntityTypes());
            List<TableAxisLabel> labels = tableConfigRepository.getTableAxisLabels();

            String entityTypes = tableConfigurations.stream()
                    .map(TableConfiguration::getEntityType)
                    .collect(Collectors.joining(", "));

            // Extracting a list of all post-coordination axes
            List<String> postCoordinationAxes = tableConfigurations.stream()
                    .flatMap(e -> e.getPostCoordinationAxes().stream())
                    .toList();

            List<CompositeAxis> compositeAxes = tableConfigurations.stream()
                    .flatMap(e -> e.getCompositePostCoordinationAxes().stream())
                    .toList();

            TableConfiguration mergedConfiguration = new TableConfiguration(entityTypes, postCoordinationAxes, compositeAxes);
            return Mono.just(new GetTablePostCoordinationAxisResponse(mergedConfiguration, labels));
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            LOGGER.error("Error fetching entity types",e);
            return Mono.error(new MessageProcessingException("Error fetching entity types", e));
        }
    }
}
