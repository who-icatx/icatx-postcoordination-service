package edu.stanford.protege.webprotege.postcoordinationservice.uiHistoryConcern.changes;

import edu.stanford.protege.webprotege.change.ProjectChange;
import edu.stanford.protege.webprotege.common.*;
import edu.stanford.protege.webprotege.diff.DiffElement;
import edu.stanford.protege.webprotege.entity.EntityNode;
import edu.stanford.protege.webprotege.ipc.*;
import edu.stanford.protege.webprotege.ipc.util.CorrelationMDCUtil;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.*;
import edu.stanford.protege.webprotege.postcoordinationservice.events.*;
import edu.stanford.protege.webprotege.postcoordinationservice.model.*;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationTableConfigRepository;
import edu.stanford.protege.webprotege.postcoordinationservice.services.LinearizationService;
import edu.stanford.protege.webprotege.postcoordinationservice.uiHistoryConcern.diff.*;
import edu.stanford.protege.webprotege.postcoordinationservice.uiHistoryConcern.nodeRendering.EntityRendererManager;
import edu.stanford.protege.webprotege.revision.RevisionNumber;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.*;

import static edu.stanford.protege.webprotege.postcoordinationservice.mappers.SpecificationToEventsMapper.groupScaleEventsByAxis;


@Component
public class ProjectChangesManager {

    private static final Logger logger = LoggerFactory.getLogger(ProjectChangesManager.class);

    private final Revision2DiffElementsTranslator revision2DiffElementsTranslator;

    private final EntityRendererManager entityRendererManager;

    private final PostCoordinationTableConfigRepository tableConfigurationRepo;

    private final LinearizationService linearizationService;
    private final CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypesExecutor;


    public ProjectChangesManager(
            Revision2DiffElementsTranslator revision2DiffElementsTranslator,
            EntityRendererManager entityRendererManager,
            PostCoordinationTableConfigRepository tableConfigurationRepo,
            LinearizationService linearizationService, CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypesExecutor) {
        this.revision2DiffElementsTranslator = revision2DiffElementsTranslator;
        this.entityRendererManager = entityRendererManager;
        this.tableConfigurationRepo = tableConfigurationRepo;
        this.linearizationService = linearizationService;
        this.entityTypesExecutor = entityTypesExecutor;
    }

    public ProjectChangeForEntity getProjectChangesForCustomScaleRevision(ProjectId projectId, String whoficEntityIri, PostCoordinationCustomScalesRevision revision, String commitMessage) {
        Map<String, String> entityIrisAndNames = new HashMap<>();
        entityIrisAndNames.put(whoficEntityIri, whoficEntityIri);
        List<TableAxisLabel> tableAxisLabels = tableConfigurationRepo.getTableAxisLabels();
        tableAxisLabels.forEach(tableAxisLabel ->
                entityIrisAndNames.put(tableAxisLabel.getPostCoordinationAxis(), tableAxisLabel.getScaleLabel())
        );

        revision.postCoordinationEvents()
                .forEach(event -> entityIrisAndNames.put(event.getPostCoordinationScaleValue(), event.getPostCoordinationScaleValue()));
        List<EntityNode> renderedEntitiesList = entityRendererManager.getRenderedEntities(entityIrisAndNames.keySet(), projectId);

        renderedEntitiesList.forEach(renderedEntity -> {
            if (entityIrisAndNames.get(renderedEntity.getEntity().toStringID()) != null && !renderedEntity.getBrowserText().equals("")) {
                entityIrisAndNames.put(renderedEntity.getEntity().toStringID(), renderedEntity.getBrowserText());
            }
        });

        ProjectChange projectChange = getProjectChangesForCustomScaleRevision(
                revision,
                entityIrisAndNames.get(whoficEntityIri),
                entityIrisAndNames,
                projectId,
                whoficEntityIri,
                commitMessage
        );

        return ProjectChangeForEntity.create(
                whoficEntityIri,
                projectChange
        );
    }

    private ProjectChange getProjectChangesForCustomScaleRevision(PostCoordinationCustomScalesRevision revision,
                                                                  String subjectName,
                                                                  Map<String, String> entityIrisAndNames,
                                                                  ProjectId projectId,
                                                                  String whofiEntityIri,
                                                                  String commitMessage) {
        final int totalChanges;
        var changesByAxis = groupScaleEventsByAxis(revision.postCoordinationEvents().stream().toList());
        totalChanges = changesByAxis.size();


        List<DiffElement<CustomScaleDocumentChange, PostCoordinationCustomScalesValueEvent>> diffElements = revision2DiffElementsTranslator.getDiffElementsFromCustomScaleRevision(changesByAxis,
                createOrderAxisMapWithSubAxis(whofiEntityIri, projectId, revision.userId()), entityIrisAndNames);
        List<DiffElement<CustomScaleDocumentChange, PostCoordinationCustomScalesValueEvent>> mutableDiffElements = new ArrayList<>(diffElements);
        mutableDiffElements.sort(Comparator.comparing(diffElement -> diffElement.getSourceDocument().getSortingCode()));

        List<DiffElement<String, String>> renderedDiffElements = renderDiffElementsForCustomScale(mutableDiffElements, entityIrisAndNames);
        int pageElements = renderedDiffElements.size();
        int pageCount;
        if (pageElements == 0) {
            pageCount = 1;
        } else {
            pageCount = totalChanges / pageElements + (totalChanges % pageElements);
        }
        Page<DiffElement<String, String>> page = Page.create(
                1,
                pageCount,
                renderedDiffElements,
                totalChanges
        );
        var message = commitMessage != null ? commitMessage : "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"cursor : pointer;\" onclick=\"window.focusClickedEntity && window.focusClickedEntity(event, '")
                .append(whofiEntityIri)
                .append("')\" title=\"Click to select entity ")
                .append(whofiEntityIri)
                .append("\">");
        sb.append("Edited Postcoordination Scale Values for Entity: ").append(subjectName).append(" : ").append(message);
        sb.append("</div>");
        return ProjectChange.get(
                RevisionNumber.valueOf("0"),
                revision.userId(),
                revision.timestamp(),
                sb.toString(),
                totalChanges,
                page);
    }

    private List<DiffElement<String, String>> renderDiffElementsForCustomScale(List<DiffElement<CustomScaleDocumentChange, PostCoordinationCustomScalesValueEvent>> diffElements, Map<String, String> entityIrisAndNames) {

        List<DiffElement<String, String>> renderedDiffElements = new ArrayList<>();
        DiffElementRenderer<String> renderer = new DiffElementRenderer<>(entityIrisAndNames);
        for (DiffElement<CustomScaleDocumentChange, PostCoordinationCustomScalesValueEvent> diffElement : diffElements) {
            renderedDiffElements.add(renderer.render(diffElement));
        }
        return renderedDiffElements;
    }

    public Set<ProjectChangeForEntity> getProjectChangesForCustomScaleHistories(ProjectId projectId, List<EntityCustomScalesValuesHistory> entityCustomScaleHistories) {
        Map<String, String> entityIrisAndNames = new HashMap<>();

        Set<CustomScaleRevisionWithEntity> scaleRevisions = entityCustomScaleHistories.stream()
                .flatMap(history ->
                        history.getPostCoordinationCustomScalesRevisions()
                                .stream()
                                .map(revision -> new CustomScaleRevisionWithEntity(revision, history.getWhoficEntityIri()))
                )
                .sorted(Comparator.comparing(CustomScaleRevisionWithEntity::getRevision))
                .peek(revisionWithEntity -> {
                    entityIrisAndNames.put(revisionWithEntity.getWhoficEntityIri(), revisionWithEntity.getWhoficEntityIri());
                    revisionWithEntity.getRevision().postCoordinationEvents()
                            .forEach(event -> entityIrisAndNames.put(event.getPostCoordinationScaleValue(), event.getPostCoordinationScaleValue()));
                })
                .collect(Collectors.toSet());

        List<EntityNode> renderedEntitiesList = entityRendererManager.getRenderedEntities(entityIrisAndNames.keySet(), projectId);

        renderedEntitiesList.forEach(renderedEntity -> {
            if (entityIrisAndNames.get(renderedEntity.getEntity().toStringID()) != null) {
                entityIrisAndNames.put(renderedEntity.getEntity().toStringID(), renderedEntity.getBrowserText());
            }
        });

        Set<ProjectChangeForEntity> projectChangeForEntityList = scaleRevisions.stream()
                .flatMap(revisionWithEntity -> {
                            ProjectChange projectChange = getProjectChangesForCustomScaleRevision(
                                    revisionWithEntity.getRevision(),
                                    entityIrisAndNames.get(revisionWithEntity.getWhoficEntityIri()),
                                    entityIrisAndNames,
                                    projectId,
                                    revisionWithEntity.getWhoficEntityIri(),
                                    ""
                            );
                            ProjectChangeForEntity projectChangeForEntity = ProjectChangeForEntity.create(
                                    revisionWithEntity.getWhoficEntityIri(),
                                    projectChange
                            );
                            return Stream.of(projectChangeForEntity);
                        }
                )
                .collect(Collectors.toSet());

        return projectChangeForEntityList;
    }


    private Map<String, Integer> createOrderAxisMapWithSubAxis(String whoficEntityiri, ProjectId projectId, UserId userId) {
        Map<String, Integer> orderedAxisMap = new HashMap<>();
        try {
            GetIcatxEntityTypeResponse typeResponse = entityTypesExecutor.execute(new GetIcatxEntityTypeRequest(IRI.create(whoficEntityiri), projectId), new ExecutionContext(userId, "", CorrelationMDCUtil.getCorrelationId()))
                    .get(15, TimeUnit.SECONDS);
            List<TableConfiguration> tableConfiguration = tableConfigurationRepo.getTableConfigurationByEntityType(typeResponse.icatxEntityTypes());

            if (tableConfiguration == null) {
                return Collections.emptyMap();
            }
            List<String> orderedAxisList = tableConfiguration.stream()
                    .flatMap(c -> c.getPostCoordinationAxes().stream()).distinct()
                    .collect(Collectors.toList());


            List<CompositeAxis> compositeAxisList = tableConfiguration.stream().flatMap(c -> c.getCompositePostCoordinationAxes().stream()).toList();

            compositeAxisList.forEach(compositeAxis -> {
                int indexForCurrAxis = orderedAxisList.indexOf(compositeAxis.getPostCoordinationAxis());

                if (indexForCurrAxis != -1) {
                    List<String> subAxisList = new LinkedList<>(compositeAxis.getSubAxis());
                    orderedAxisList.addAll(indexForCurrAxis + 1, subAxisList);
                    orderedAxisList.remove(indexForCurrAxis);
                }
            });

            orderedAxisMap = IntStream.range(0, orderedAxisList.size())
                    .boxed()
                    .collect(Collectors.toMap(orderedAxisList::get, index -> index));

            return orderedAxisMap;
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }


    public Set<ProjectChangeForEntity> getProjectChangesForSpecHistories(ProjectId projectId, List<EntityPostCoordinationHistory> entitySpecHistories) {
        Map<String, String> entityIrisAndNames = new HashMap<>();
        List<LinearizationDefinition> linearizationDefinitions = linearizationService.getLinearizationDefinitions();
        linearizationDefinitions.forEach(linDef -> entityIrisAndNames.put(linDef.getLinearizationUri(), linDef.getDisplayLabel()));

        Set<SpecRevisionWithEntity> specRevisions = entitySpecHistories.stream()
                .flatMap(history ->
                        history.getPostCoordinationRevisions()
                                .stream()
                                .map(revision -> new SpecRevisionWithEntity(revision, projectId, history.getWhoficEntityIri()))
                )
                .sorted(Comparator.comparing(SpecRevisionWithEntity::revision))
                .peek(revisionWithEntity -> entityIrisAndNames.put(revisionWithEntity.whoficEntityIri(), revisionWithEntity.whoficEntityIri()))
                .collect(Collectors.toSet());

        List<EntityNode> renderedEntitiesList = entityRendererManager.getRenderedEntities(entityIrisAndNames.keySet(), projectId);
        renderedEntitiesList.forEach(renderedEntity -> {
            if (entityIrisAndNames.get(renderedEntity.getEntity().toStringID()) != null) {
                entityIrisAndNames.put(renderedEntity.getEntity().toStringID(), renderedEntity.getBrowserText());
            }
        });

        Set<ProjectChangeForEntity> projectChangeForEntityList = specRevisions.stream()
                .flatMap(revisionWithEntity -> {
                            ProjectChange projectChange = getProjectChangesForSpecRevision(
                                    revisionWithEntity,
                                    entityIrisAndNames.get(revisionWithEntity.whoficEntityIri()),
                                    entityIrisAndNames,
                                    ""
                            );
                            ProjectChangeForEntity projectChangeForEntity = ProjectChangeForEntity.create(
                                    revisionWithEntity.whoficEntityIri(),
                                    projectChange
                            );
                            return Stream.of(projectChangeForEntity);
                        }
                )
                .collect(Collectors.toSet());

        return projectChangeForEntityList;
    }

    private ProjectChange getProjectChangesForSpecRevision(SpecRevisionWithEntity specRevisionWithEntity,
                                                           String subjectName,
                                                           Map<String, String> entityIrisAndNames, String commitMessage) {
        final int totalChanges;
        PostCoordinationSpecificationRevision revision = specRevisionWithEntity.revision();
        var eventsByView = revision.postCoordinationEvents().stream().toList();
        totalChanges = eventsByView.size();

        List<DiffElement<SpecDocumentChange, List<PostCoordinationSpecificationEvent>>> diffElements = revision2DiffElementsTranslator.getDiffElementsFromSpecRevision(eventsByView,
                createOrderAxisMapWithSubAxis(specRevisionWithEntity.whoficEntityIri(), specRevisionWithEntity.projectId(), specRevisionWithEntity.revision().userId()));
        sortByLinViews(diffElements);

        List<DiffElement<String, String>> renderedDiffElements = renderDiffElementsForSpecRevision(diffElements, entityIrisAndNames);
        int pageElements = renderedDiffElements.size();
        int pageCount;
        if (pageElements == 0) {
            pageCount = 1;
        } else {
            pageCount = totalChanges / pageElements + (totalChanges % pageElements);
        }
        Page<DiffElement<String, String>> page = Page.create(
                1,
                pageCount,
                renderedDiffElements,
                totalChanges
        );
        var message = commitMessage != null ? commitMessage : "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"cursor : pointer;\" onclick=\"window.focusClickedEntity && window.focusClickedEntity(event, '")
                .append(specRevisionWithEntity.whoficEntityIri())
                .append("')\" title=\"Click to select entity ")
                .append(specRevisionWithEntity.whoficEntityIri())
                .append("\">");
        sb.append("Edited Postcoordination Specification for Entity: ").append(subjectName).append(": ").append(message);
        sb.append("</div>");

        return ProjectChange.get(
                RevisionNumber.valueOf("0"),
                revision.userId(),
                revision.timestamp(),
                sb.toString(),
                totalChanges,
                page);
    }

    private List<DiffElement<String, String>> renderDiffElementsForSpecRevision(List<DiffElement<SpecDocumentChange, List<PostCoordinationSpecificationEvent>>> diffElements, Map<String, String> entityIrisAndNames) {
        List<DiffElement<String, String>> renderedDiffElements = new ArrayList<>();
        DiffElementRenderer<String> renderer = new DiffElementRenderer<>(entityIrisAndNames);
        for (DiffElement<SpecDocumentChange, List<PostCoordinationSpecificationEvent>> diffElement : diffElements) {
            renderedDiffElements.add(renderer.renderSpec(diffElement));
        }
        return renderedDiffElements;
    }

    private void sortByLinViews(List<DiffElement<SpecDocumentChange, List<PostCoordinationSpecificationEvent>>> diffElements) {
        diffElements.sort(Comparator.comparing(diffElement -> diffElement.getSourceDocument().getSortingCode()));
    }


    public ProjectChangeForEntity getProjectChangesForSpecRevision(ProjectId projectId, String whoficEntityIri, PostCoordinationSpecificationRevision revision, String commitMessage) {
        Map<String, String> entityIrisAndNames = new HashMap<>();
        entityIrisAndNames.put(whoficEntityIri, whoficEntityIri);
        revision.postCoordinationEvents()
                .stream()
                .flatMap(event -> event.axisEvents().stream())
                .forEach(specEvent -> {
                    entityIrisAndNames.computeIfAbsent(specEvent.getPostCoordinationAxis(), k -> specEvent.getPostCoordinationAxis());
                    entityIrisAndNames.computeIfAbsent(specEvent.getLinearizationView(), k -> specEvent.getLinearizationView());

                });

        List<EntityNode> renderedEntitiesList = entityRendererManager.getRenderedEntities(Set.of(whoficEntityIri), projectId);
        renderedEntitiesList.forEach(renderedEntity -> {
            if (entityIrisAndNames.get(renderedEntity.getEntity().toStringID()) != null) {
                entityIrisAndNames.put(renderedEntity.getEntity().toStringID(), renderedEntity.getBrowserText());
            }
        });
        List<LinearizationDefinition> linDefs = linearizationService.getLinearizationDefinitions();
        linDefs.forEach(linDef -> {
            if (entityIrisAndNames.get(linDef.getLinearizationUri()) != null) {
                entityIrisAndNames.put(linDef.getLinearizationUri(), linDef.getDisplayLabel());
            }
        });

        List<TableAxisLabel> tableAxisLabels = tableConfigurationRepo.getTableAxisLabels();
        tableAxisLabels.forEach(tableAxis -> {
            if (entityIrisAndNames.get(tableAxis.getPostCoordinationAxis()) != null) {
                entityIrisAndNames.put(tableAxis.getPostCoordinationAxis(), tableAxis.getTableLabel());
            }
        });

        ProjectChange projectChange = getProjectChangesForSpecRevision(
                new SpecRevisionWithEntity(revision, projectId, whoficEntityIri),
                entityIrisAndNames.get(whoficEntityIri),
                entityIrisAndNames,
                commitMessage
        );

        return ProjectChangeForEntity.create(
                whoficEntityIri,
                projectChange
        );
    }
}
