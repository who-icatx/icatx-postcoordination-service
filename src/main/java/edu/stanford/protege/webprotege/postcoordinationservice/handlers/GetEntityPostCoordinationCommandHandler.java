package edu.stanford.protege.webprotege.postcoordinationservice.handlers;


import edu.stanford.protege.webprotege.ipc.*;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetEntityPostCoordinationRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetEntityPostCoordinationResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.services.*;
import org.jetbrains.annotations.NotNull;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@WebProtegeHandler
public class GetEntityPostCoordinationCommandHandler implements CommandHandler<GetEntityPostCoordinationRequest, GetEntityPostCoordinationResponse> {

    private final Logger LOGGER = LoggerFactory.getLogger(GetEntityPostCoordinationCommandHandler.class);

    private final PostCoordinationService postCoordService;

    private final CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor;

    public GetEntityPostCoordinationCommandHandler(PostCoordinationService postCoordService, CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor) {
        this.postCoordService = postCoordService;
        this.entityTypeExecutor = entityTypeExecutor;
    }

    @NotNull
    @Override
    public String getChannelName() {
        return GetEntityPostCoordinationRequest.CHANNEL;
    }

    @Override
    public Class<GetEntityPostCoordinationRequest> getRequestClass() {
        return GetEntityPostCoordinationRequest.class;
    }

    @Override
    public Mono<GetEntityPostCoordinationResponse> handleRequest(GetEntityPostCoordinationRequest request, ExecutionContext executionContext) {
        try {
            List<String> entityTypes = entityTypeExecutor.execute(new GetIcatxEntityTypeRequest(IRI.create(request.entityIRI()), request.projectId()), executionContext)
                    .get(15, TimeUnit.SECONDS).icatxEntityTypes();
            LOGGER.info("Fetched entity type for {} : {} ", request.entityIRI(), entityTypes);
            GetEntityPostCoordinationResponse processedSpec = postCoordService.fetchHistory(request.entityIRI(), request.projectId(),  entityTypes);

            return Mono.just(processedSpec);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            LOGGER.error("Error fetching entity types",e);
            return Mono.error(new MessageProcessingException("Error fetching entity types", e));
        }
    }
}
