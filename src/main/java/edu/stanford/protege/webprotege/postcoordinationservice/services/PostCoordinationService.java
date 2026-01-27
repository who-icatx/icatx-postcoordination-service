package edu.stanford.protege.webprotege.postcoordinationservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.*;
import edu.stanford.protege.webprotege.common.*;
import edu.stanford.protege.webprotege.ipc.*;
import edu.stanford.protege.webprotege.ipc.util.CorrelationMDCUtil;
import edu.stanford.protege.webprotege.postcoordinationservice.StreamUtils;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.*;
import edu.stanford.protege.webprotege.postcoordinationservice.events.PostCoordinationCustomScalesValueEvent;
import edu.stanford.protege.webprotege.postcoordinationservice.mappers.SpecificationToEventsMapper;
import edu.stanford.protege.webprotege.postcoordinationservice.model.*;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.*;
import org.bson.Document;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static edu.stanford.protege.webprotege.postcoordinationservice.model.EntityCustomScalesValuesHistory.POSTCOORDINATION_CUSTOM_SCALES_COLLECTION;
import static edu.stanford.protege.webprotege.postcoordinationservice.model.EntityPostCoordinationHistory.POSTCOORDINATION_HISTORY_COLLECTION;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

@Service
public class PostCoordinationService {

    private final static Logger LOGGER = LoggerFactory.getLogger(PostCoordinationService.class);
    private final PostCoordinationRepository repository;
    private final PostCoordinationTableConfigRepository configRepository;
    private final LinearizationService linearizationService;
    private final ReadWriteLockService readWriteLock;
    private final PostCoordinationDocumentRepository documentRepository;
    private final ObjectMapper objectMapper;
    private final NewRevisionsEventEmitterService newRevisionsEventEmitter;

    private final PostCoordinationEventProcessor eventProcessor;

    private final CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor;


    public PostCoordinationService(PostCoordinationRepository repository,
                                   PostCoordinationTableConfigRepository configRepository,
                                   LinearizationService linearizationService,
                                   ReadWriteLockService readWriteLock,
                                   PostCoordinationDocumentRepository documentRepository,
                                   ObjectMapper objectMapper,
                                   NewRevisionsEventEmitterService newRevisionsEventEmitter, PostCoordinationEventProcessor eventProcessor, CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor) {
        this.repository = repository;
        this.configRepository = configRepository;
        this.linearizationService = linearizationService;
        this.readWriteLock = readWriteLock;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
        this.newRevisionsEventEmitter = newRevisionsEventEmitter;
        this.eventProcessor = eventProcessor;
        this.entityTypeExecutor = entityTypeExecutor;
    }


    public void createFirstSpecificationImport(String documentLocation, ProjectId projectId, UserId userId) {
        var stream = documentRepository.fetchPostCoordinationSpecifications(documentLocation);
        Set<String> availableAxes = configRepository.getALlTableConfiguration().stream()
                .flatMap(c -> c.getPostCoordinationAxes().stream())
                .collect(Collectors.toSet());
        readWriteLock.executeWriteLock(() -> {
            stream.collect(StreamUtils.batchCollector(500, createBatchProcessorForSavingPaginatedHistories(projectId, userId, availableAxes)));
        });
    }


    public void crateFirstCustomScalesValuesImport(String documentLocation, ProjectId projectId, UserId userId) {
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                LOGGER.info("Attempting to fetch custom scales from location: {}", documentLocation);
                var stream = documentRepository.fetchCustomScalesValues(documentLocation);
                stream.collect(StreamUtils.batchCollector(500, createBatchProcessorForSavingPaginatedCustomScales(projectId, userId)));
                LOGGER.info("Successfully processed custom scales from location: {}", documentLocation);
                return; // Success, exit the retry loop
            } catch (Exception e) {
                retryCount++;
                LOGGER.error("Error while processing custom scales (attempt {}/{}): {}", retryCount, maxRetries, e.getMessage(), e);
                
                if (retryCount >= maxRetries) {
                    LOGGER.error("Failed to process custom scales after {} attempts", maxRetries);
                    throw new RuntimeException("Error while processing custom scales after " + maxRetries + " attempts", e);
                }
                
                // Wait before retrying (exponential backoff)
                try {
                    long waitTime = (long) Math.pow(2, retryCount) * 1000; // 2s, 4s, 8s
                    LOGGER.info("Waiting {} ms before retry", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for retry", ie);
                }
            }
        }
    }

    private Consumer<List<WhoficCustomScalesValues>> createBatchProcessorForSavingPaginatedCustomScales(ProjectId projectId,
                                                                                                        UserId userId) {
        return page -> {
            try {
                if (isNotEmpty(page)) {
                    Set<EntityCustomScalesValuesHistory> histories = new HashSet<>();
                    for (WhoficCustomScalesValues specification : page) {
                        Set<PostCoordinationCustomScalesValueEvent> events = SpecificationToEventsMapper.convertToFirstImportEvents(specification);
                        PostCoordinationCustomScalesRevision revision = PostCoordinationCustomScalesRevision.create(userId, events);
                        EntityCustomScalesValuesHistory history = new EntityCustomScalesValuesHistory(specification.whoficEntityIri(), projectId.id(), List.of(revision));
                        histories.add(history);
                    }
                    var documents = histories.stream()
                            .map(history -> {
                                Document doc = objectMapper.convertValue(history, Document.class);
                                return new ReplaceOneModel<>(
                                        new Document(EntityCustomScalesValuesHistory.WHOFIC_ENTITY_IRI, history.getWhoficEntityIri())
                                                .append(EntityCustomScalesValuesHistory.PROJECT_ID, history.getProjectId()),
                                        doc,
                                        new ReplaceOptions().upsert(true)
                                );
                            })
                            .toList();

                    repository.bulkWriteDocuments(documents, POSTCOORDINATION_CUSTOM_SCALES_COLLECTION);
                }
            } catch (Exception e) {
                LOGGER.error("Error while processing page", e);
            }
        };
    }


    private Consumer<List<WhoficEntityPostCoordinationSpecification>> createBatchProcessorForSavingPaginatedHistories(ProjectId projectId, UserId userId, Set<String> availableAxes) {
        return page -> {
            if (isNotEmpty(page)) {
                Set<EntityPostCoordinationHistory> histories = new HashSet<>();
                for (WhoficEntityPostCoordinationSpecification specification : page) {
                    Set<PostCoordinationViewEvent> events = specification.postcoordinationSpecifications().stream()
                            .map(spec ->
                                    new PostCoordinationViewEvent(spec.getLinearizationView(), SpecificationToEventsMapper.convertFromSpecification(spec, availableAxes))
                            )
                            .filter(spec -> !spec.axisEvents().isEmpty())
                            .collect(Collectors.toSet());
                    PostCoordinationSpecificationRevision revision = PostCoordinationSpecificationRevision.create(userId, events);
                    EntityPostCoordinationHistory history = new EntityPostCoordinationHistory(specification.whoficEntityIri(), projectId.id(), List.of(revision));
                    if (!events.isEmpty()) {
                        histories.add(history);
                    }
                }
                if (!histories.isEmpty()) {
                    saveMultipleEntityPostCoordinationHistories(histories);
                }

            }
        };
    }

    private void saveMultipleEntityPostCoordinationHistories(Set<EntityPostCoordinationHistory> historiesToBeSaved) {
        var documents = historiesToBeSaved.stream()
                .map(history -> new InsertOneModel<>(objectMapper.convertValue(history, Document.class)))
                .toList();

        repository.bulkWriteDocuments(documents, POSTCOORDINATION_HISTORY_COLLECTION);
    }


    public void addSpecificationRevision(WhoficEntityPostCoordinationSpecification newSpecification, UserId userId, ProjectId projectId) {
        addSpecificationRevision(newSpecification, userId, projectId, null, null);
    }

    public void addSpecificationRevision(WhoficEntityPostCoordinationSpecification newSpecification, UserId userId, ProjectId projectId, ChangeRequestId changeRequestId, String commitMessage) {

        readWriteLock.executeWriteLock(() -> {
                    var existingHistoryOptional = this.repository.getExistingHistoryOrderedByRevision(newSpecification.whoficEntityIri(), projectId);
                    existingHistoryOptional.ifPresentOrElse(history -> {
                                if(history.getPostCoordinationRevisions().stream().noneMatch(rev -> rev.userId().id().equals("initialRevision"))){
                                    List<LinearizationDefinition> definitionList = linearizationService.getLinearizationDefinitions();
                                    List<TableConfiguration> configurations = configRepository.getALlTableConfiguration();
                                    List<String> entityTypes;
                                    try {
                                        entityTypes = entityTypeExecutor.execute(new GetIcatxEntityTypeRequest(IRI.create(newSpecification.whoficEntityIri()), projectId), new ExecutionContext(userId, "", CorrelationMDCUtil.getCorrelationId()))
                                                .get(15, TimeUnit.SECONDS).icatxEntityTypes();
                                    } catch (TimeoutException | InterruptedException | ExecutionException e) {
                                        throw new MessageProcessingException("Error fetching entity types", e);
                                    }

                                    history.getPostCoordinationRevisions().add(0, PostCoordinationSpecificationRevision.createDefaultInitialRevision(
                                            entityTypes,
                                            definitionList,
                                            configurations));

                                }
                                WhoficEntityPostCoordinationSpecification oldSpec = eventProcessor.processHistory(history);
                                Set<PostCoordinationViewEvent> specEvents = SpecificationToEventsMapper.createEventsFromDiff(oldSpec, newSpecification);

                                if (!specEvents.isEmpty()) {
                                    var newRevision = PostCoordinationSpecificationRevision.create(userId, specEvents, changeRequestId);
                                    repository.addSpecificationRevision(newSpecification.whoficEntityIri(), projectId, newRevision);
                                    newRevisionsEventEmitter.emitNewRevisionsEvent(projectId, newSpecification.whoficEntityIri(), newRevision, changeRequestId, commitMessage);
                                }
                            }, () -> {
                                EntityPostCoordinationHistory history = createNewSpecificationHistory(newSpecification, projectId, userId, changeRequestId);
                                if(history != null) {
                                    var savedHistory = repository.saveNewSpecificationHistory(history);
                                    if (!newSpecification.postcoordinationSpecifications().isEmpty()) {
                                        savedHistory.getPostCoordinationRevisions()
                                                .stream()
                                                .findFirst()
                                                .ifPresent(revision -> newRevisionsEventEmitter.emitNewRevisionsEvent(projectId, savedHistory.getWhoficEntityIri(), revision, changeRequestId, commitMessage));
                                    }
                                }

                            }
                    );
                }
        );
    }

    public void addCustomScaleRevision(WhoficCustomScalesValues newScales,
                                       ProjectId projectId,
                                       UserId userId) {
        addCustomScaleRevision(newScales, projectId, userId, null, null);
    }

    public void addCustomScaleRevision(WhoficCustomScalesValues newScales,
                                       ProjectId projectId,
                                       UserId userId,
                                       ChangeRequestId changeRequestId,
                                       String commitMessage) {
        readWriteLock.executeWriteLock(() -> {
                    var existingScaleHistoryOptional = this.repository.getExistingCustomScaleHistoryOrderedByRevision(newScales.whoficEntityIri(), projectId);
                    existingScaleHistoryOptional.ifPresentOrElse(history -> {

                                WhoficCustomScalesValues oldSpec = eventProcessor.processCustomScaleHistory(history);

                                Set<PostCoordinationCustomScalesValueEvent> events = SpecificationToEventsMapper.createScaleEventsFromDiff(oldSpec, newScales);


                                if (!events.isEmpty()) {
                                    var newRevision = PostCoordinationCustomScalesRevision.create(userId, events, changeRequestId);
                                    repository.addCustomScalesRevision(newScales.whoficEntityIri(), projectId, newRevision);
                                    newRevisionsEventEmitter.emitNewRevisionsEvent(projectId, newScales.whoficEntityIri(), newRevision, changeRequestId, commitMessage);
                                }
                            }, () -> {
                                var newHistory = createNewEntityCustomScalesHistory(newScales, projectId, userId, changeRequestId);
                                var savedHistory = repository.saveNewCustomScalesHistory(newHistory);
                                savedHistory.getPostCoordinationCustomScalesRevisions()
                                        .stream()
                                        .findFirst()
                                        .ifPresent(revision -> {
                                            if (!revision.postCoordinationEvents().isEmpty()) {
                                                newRevisionsEventEmitter.emitNewRevisionsEvent(projectId, newScales.whoficEntityIri(), revision, changeRequestId, commitMessage);
                                            }
                                        });
                            }
                    );
                }
        );
    }

    private EntityCustomScalesValuesHistory createNewEntityCustomScalesHistory(WhoficCustomScalesValues newScales,
                                                                               ProjectId projectId,
                                                                               UserId userId,
                                                                               ChangeRequestId changeRequestId) {
        WhoficCustomScalesValues oldSpec = WhoficCustomScalesValues.create(newScales.whoficEntityIri(), Collections.emptyList());
        Set<PostCoordinationCustomScalesValueEvent> events = SpecificationToEventsMapper.createScaleEventsFromDiff(oldSpec, newScales);
        var revision = PostCoordinationCustomScalesRevision.create(userId, events, changeRequestId);
        return EntityCustomScalesValuesHistory.create(newScales.whoficEntityIri(), projectId.value(), List.of(revision));
    }

    private EntityPostCoordinationHistory createNewSpecificationHistory(WhoficEntityPostCoordinationSpecification newSpec,
                                                                        ProjectId projectId,
                                                                        UserId userId,
                                                                        ChangeRequestId changeRequestId) {
        List<LinearizationDefinition> definitionList = linearizationService.getLinearizationDefinitions();
        List<TableConfiguration> configurations = configRepository.getALlTableConfiguration();
        List<String> entityTypes;
        try {
            entityTypes = entityTypeExecutor.execute(new GetIcatxEntityTypeRequest(IRI.create(newSpec.whoficEntityIri()), projectId), new ExecutionContext(userId, "", CorrelationMDCUtil.getCorrelationId()))
                    .get(15, TimeUnit.SECONDS).icatxEntityTypes();
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            throw new MessageProcessingException("Error fetching entity types", e);
        }
        var defaultRevision = PostCoordinationSpecificationRevision.createDefaultInitialRevision(entityTypes,
                definitionList,
                configurations);

        WhoficEntityPostCoordinationSpecification defaultSpec = eventProcessor.processHistory(new EntityPostCoordinationHistory(newSpec.whoficEntityIri(), projectId.id(), Arrays.asList(defaultRevision)));
        Set<PostCoordinationViewEvent> specEvents = SpecificationToEventsMapper.createEventsFromDiff(defaultSpec, newSpec);
        if(specEvents.isEmpty()) {
            return null;
        }
        var newRevision = PostCoordinationSpecificationRevision.create(userId, specEvents, changeRequestId);
        return EntityPostCoordinationHistory.create(newSpec.whoficEntityIri(), projectId.id(), List.of(newRevision));
    }

    public GetEntityCustomScaleValueResponse fetchCustomScalesHistory(String entityIri, ProjectId projectId, ExecutionContext executionContext) {
        List<TableConfiguration> configurations = configRepository.getALlTableConfiguration();

        try {
            List<String> entityTypes = entityTypeExecutor.execute(new GetIcatxEntityTypeRequest(IRI.create(entityIri), projectId), executionContext)
                    .get(15, TimeUnit.SECONDS).icatxEntityTypes();
            return this.repository.getExistingCustomScaleHistoryOrderedByRevision(entityIri, projectId)
                    .map(history -> {
                        Date lastRevisionDate = null;
                        if (!history.getPostCoordinationCustomScalesRevisions().isEmpty()) {
                            long lastRevisionTimestamp = history.getPostCoordinationCustomScalesRevisions().get(history.getPostCoordinationCustomScalesRevisions().size() - 1).timestamp();
                            lastRevisionDate = Date.from(Instant.ofEpochMilli(lastRevisionTimestamp));
                        }
                        WhoficCustomScalesValues scales = eventProcessor.processCustomScaleHistory(history);
                        Set<String> postCoordinationAxis = configurations.stream()
                                .filter(config -> entityTypes.contains(config.getEntityType()))
                                .flatMap(config -> {
                                        List<String> axes = new ArrayList<>(List.copyOf(config.getPostCoordinationAxes()));
                                        axes.addAll(config.getCompositePostCoordinationAxes()
                                                .stream().flatMap(c -> c.getSubAxis().stream()).toList());

                                        return axes.stream();
                                        }
                                        )
                                .collect(Collectors.toSet());
                        return new GetEntityCustomScaleValueResponse(lastRevisionDate, filterExtraAxis(scales, postCoordinationAxis));
                    })
                    .orElseGet(() -> new GetEntityCustomScaleValueResponse(null, new WhoficCustomScalesValues(entityIri, Collections.emptyList())));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Error fetching entity types", e);
            throw new MessageProcessingException("Error fetching entity types", e);
        }
    }

    public GetEntityPostCoordinationResponse fetchHistory(String entityIri, ProjectId projectId, List<String> entityTypes) {
        List<LinearizationDefinition> definitionList = linearizationService.getLinearizationDefinitions();
        List<TableConfiguration> configurations = configRepository.getALlTableConfiguration();
        Set<String> postCoordinationAxis = configurations.stream()
                .filter(config -> entityTypes.contains(config.getEntityType()))
                .flatMap(config -> config.getPostCoordinationAxes().stream())
                .collect(Collectors.toSet());

        return this.repository.getExistingHistoryOrderedByRevision(entityIri, projectId)
                .map(history -> {
                            history.getPostCoordinationRevisions().add(0, PostCoordinationSpecificationRevision.createDefaultInitialRevision(
                                    entityTypes,
                                    definitionList,
                                    configurations));

                            return new GetEntityPostCoordinationResponse(entityIri, filterExtraSpecifications(postCoordinationAxis, eventProcessor.processHistory(history)));
                        }
                )
                .orElseGet(() -> {
                    var specs = Arrays.asList(PostCoordinationSpecificationRevision.createDefaultInitialRevision(entityTypes, definitionList, configurations));
                    var history = new EntityPostCoordinationHistory(entityIri, projectId.id(), specs);
                    return new GetEntityPostCoordinationResponse(entityIri, filterExtraSpecifications(postCoordinationAxis, eventProcessor.processHistory(history)));
                });
    }


    private WhoficCustomScalesValues filterExtraAxis(WhoficCustomScalesValues rawCustomScales, Set<String> allowedPostCoordAxis) {
        List<PostCoordinationScaleCustomization> filteredScales = rawCustomScales.scaleCustomizations().stream().filter(rawCustomization ->
                allowedPostCoordAxis.contains(rawCustomization.getPostcoordinationAxis())
        ).toList();
        return new WhoficCustomScalesValues(rawCustomScales.whoficEntityIri(), filteredScales);
    }

    private WhoficEntityPostCoordinationSpecification filterExtraSpecifications(Set<String> allowedPostCoordAxis,
                                                                                WhoficEntityPostCoordinationSpecification processedSpec) {
        List<PostCoordinationSpecification> filteredSpecs = processedSpec.postcoordinationSpecifications().stream()
                .map(rawSpec -> {
                    List<String> allowedAxes = rawSpec.getAllowedAxes().stream().filter(allowedPostCoordAxis::contains).toList();
                    List<String> defaultAxes = rawSpec.getDefaultAxes().stream().filter(allowedPostCoordAxis::contains).toList();
                    List<String> notAllowedAxes = rawSpec.getNotAllowedAxes().stream().filter(allowedPostCoordAxis::contains).toList();
                    List<String> requiredAxes = rawSpec.getRequiredAxes().stream().filter(allowedPostCoordAxis::contains).toList();
                    return new PostCoordinationSpecification(rawSpec.getLinearizationView(), allowedAxes, defaultAxes, notAllowedAxes, requiredAxes);
                }).toList();
        return new WhoficEntityPostCoordinationSpecification(processedSpec.whoficEntityIri(), processedSpec.entityType(), filteredSpecs);
    }
}
