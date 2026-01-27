package edu.stanford.protege.webprotege.postcoordinationservice.handlers;

import edu.stanford.protege.webprotege.ipc.*;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisResult;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationSpecification;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationScaleCustomization;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyResult;
import edu.stanford.protege.webprotege.postcoordinationservice.model.PostcoordinationAxisToGenericScale;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableConfiguration;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationTableConfigRepository;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostcoordinationAxisToGenericScaleRepository;
import org.jetbrains.annotations.NotNull;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@WebProtegeHandler
public class ValidateEntityUpdateCommandHandler implements CommandHandler<ValidateEntityUpdateRequest, ValidateEntityUpdateResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidateEntityUpdateCommandHandler.class);

    private final CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor;
    private final PostCoordinationTableConfigRepository configRepository;
    private final CommandExecutor<CheckNonExistentIrisAction, CheckNonExistentIrisResult> checkNonExistentIrisExecutor;
    private final CommandExecutor<ValidateAxisBelongsToHierarchyAction, ValidateAxisBelongsToHierarchyResult> validateAxisBelongsToHierarchyExecutor;
    private final PostcoordinationAxisToGenericScaleRepository axisToGenericScaleRepository;

    public ValidateEntityUpdateCommandHandler(CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor,
                                             PostCoordinationTableConfigRepository configRepository,
                                             CommandExecutor<CheckNonExistentIrisAction, CheckNonExistentIrisResult> checkNonExistentIrisExecutor,
                                             CommandExecutor<ValidateAxisBelongsToHierarchyAction, ValidateAxisBelongsToHierarchyResult> validateAxisBelongsToHierarchyExecutor,
                                             PostcoordinationAxisToGenericScaleRepository axisToGenericScaleRepository) {
        this.entityTypeExecutor = entityTypeExecutor;
        this.configRepository = configRepository;
        this.checkNonExistentIrisExecutor = checkNonExistentIrisExecutor;
        this.validateAxisBelongsToHierarchyExecutor = validateAxisBelongsToHierarchyExecutor;
        this.axisToGenericScaleRepository = axisToGenericScaleRepository;
    }

    @NotNull
    @Override
    public String getChannelName() {
        return ValidateEntityUpdateRequest.CHANNEL;
    }

    @Override
    public Class<ValidateEntityUpdateRequest> getRequestClass() {
        return ValidateEntityUpdateRequest.class;
    }

    @Override
    public Mono<ValidateEntityUpdateResponse> handleRequest(ValidateEntityUpdateRequest request, ExecutionContext executionContext) {
        List<String> errorMessages = new ArrayList<>();
        errorMessages.addAll(validatePostcoordinationAxisByEntityType(request, executionContext));
        errorMessages.addAll(validateScaleValuesExist(request, executionContext));
        errorMessages.addAll(validateCustomScalesAgainstSpecifications(request));
        errorMessages.addAll(validateScaleValuesBelongToAxisHierarchy(request, executionContext));
        return Mono.just(new ValidateEntityUpdateResponse(errorMessages));
    }

    private List<String> validatePostcoordinationAxisByEntityType(ValidateEntityUpdateRequest request, ExecutionContext executionContext) {
        List<String> errorMessages = new ArrayList<>();

        try {
            // Obținem entityIri (aceeași valoare în ambele atribute)
            String entityIri = request.entityCustomScaleValues().whoficEntityIri();
            
            // Verificăm că entityIri este același în ambele atribute
            if (!entityIri.equals(request.entitySpecification().whoficEntityIri())) {
                errorMessages.add("Entity IRI mismatch: entityCustomScaleValues.whoficEntityIri (" + 
                    entityIri + ") differs from entitySpecification.whoficEntityIri (" + 
                    request.entitySpecification().whoficEntityIri() + ")");
                return errorMessages;
            }

            // Fetch entityType pentru entityIri
            List<String> entityTypes = entityTypeExecutor.execute(
                    new GetIcatxEntityTypeRequest(IRI.create(entityIri), request.projectId()), 
                    executionContext)
                    .get(15, TimeUnit.SECONDS).icatxEntityTypes();

            LOGGER.info("Fetched entity types for {} : {}", entityIri, entityTypes);

            // Obținem configurațiile de tabel pentru entityType-urile găsite
            List<TableConfiguration> configurations = configRepository.getTableConfigurationByEntityType(entityTypes);

            // Extragem toate axele permise pentru acest entityType (inclusiv sub-axele din axe compozite)
            Set<String> allowedAxes = configurations.stream()
                    .filter(config -> entityTypes.contains(config.getEntityType()))
                    .flatMap(config -> {
                        List<String> axes = new ArrayList<>(List.copyOf(config.getPostCoordinationAxes()));
                        axes.addAll(config.getCompositePostCoordinationAxes()
                                .stream()
                                .flatMap(c -> c.getSubAxis().stream())
                                .toList());
                        return axes.stream();
                    })
                    .collect(Collectors.toSet());

            // Validăm axele din entitySpecification
            for (PostCoordinationSpecification spec : request.entitySpecification().postcoordinationSpecifications()) {
                validateAxes(spec.getAllowedAxes(), "allowedAxes", allowedAxes, errorMessages);
                validateAxes(spec.getDefaultAxes(), "defaultAxes", allowedAxes, errorMessages);
                validateAxes(spec.getNotAllowedAxes(), "notAllowedAxes", allowedAxes, errorMessages);
                validateAxes(spec.getRequiredAxes(), "requiredAxes", allowedAxes, errorMessages);
            }

            // Validăm axele din entityCustomScaleValues
            for (PostCoordinationScaleCustomization customization : request.entityCustomScaleValues().scaleCustomizations()) {
                String axis = customization.getPostcoordinationAxis();
                if (axis != null && !allowedAxes.contains(axis)) {
                    errorMessages.add("Axis '" + axis + "' from entityCustomScaleValues is not allowed for entityType(s): " + entityTypes);
                }
            }

        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            LOGGER.error("Error fetching entity types", e);
            errorMessages.add("Error fetching entity types: " + e.getMessage());
        }

        return errorMessages;
    }

    private void validateAxes(List<String> axes, String axisType, Set<String> allowedAxes, List<String> errorMessages) {
        if (axes != null) {
            for (String axis : axes) {
                if (axis != null && !allowedAxes.contains(axis)) {
                    errorMessages.add("Axis '" + axis + "' from " + axisType + " is not allowed for the entity's entityType");
                }
            }
        }
    }

    private List<String> validateScaleValuesExist(ValidateEntityUpdateRequest request, ExecutionContext executionContext) {
        List<String> errorMessages = new ArrayList<>();

        try {
            // Extragem toate IRI-urile din postcoordinationScaleValues
            Set<IRI> scaleValueIris = request.entityCustomScaleValues().scaleCustomizations().stream()
                    .flatMap(customization -> customization.getPostcoordinationScaleValues().stream())
                    .filter(iriString -> iriString != null && !iriString.isEmpty())
                    .map(IRI::create)
                    .collect(Collectors.toSet());

            if (scaleValueIris.isEmpty()) {
                return errorMessages;
            }

            // Verificăm care IRI-uri nu există în proiect
            CheckNonExistentIrisResult result = checkNonExistentIrisExecutor.execute(
                    new CheckNonExistentIrisAction(request.projectId(), scaleValueIris),
                    executionContext
            ).get(15, TimeUnit.SECONDS);

            if (!result.nonExistentIris().isEmpty()) {
                for (IRI nonExistentIri : result.nonExistentIris()) {
                    errorMessages.add("Scale value IRI '" + nonExistentIri + "' does not exist in project");
                }
            }

        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            LOGGER.error("Error validating scale values existence", e);
            errorMessages.add("Error validating scale values existence: " + e.getMessage());
        }

        return errorMessages;
    }

    private List<String> validateCustomScalesAgainstSpecifications(ValidateEntityUpdateRequest request) {
        List<String> errorMessages = new ArrayList<>();

        // Construim un set cu toate axele permise din specifications (allowedAxes și requiredAxes)
        Set<String> allowedOrRequiredAxes = request.entitySpecification().postcoordinationSpecifications().stream()
                .flatMap(spec -> {
                    Set<String> axes = new HashSet<>();
                    if (spec.getAllowedAxes() != null) {
                        axes.addAll(spec.getAllowedAxes());
                    }
                    if (spec.getRequiredAxes() != null) {
                        axes.addAll(spec.getRequiredAxes());
                    }
                    return axes.stream();
                })
                .collect(Collectors.toSet());

        // Pentru fiecare custom scale, verificăm dacă axa sa este în allowedAxes sau requiredAxes
        for (PostCoordinationScaleCustomization customization : request.entityCustomScaleValues().scaleCustomizations()) {
            String axis = customization.getPostcoordinationAxis();
            if (axis != null && !allowedOrRequiredAxes.contains(axis)) {
                errorMessages.add("Axis '" + axis + "' from entityCustomScaleValues is not present in allowedAxes or requiredAxes of any PostCoordinationSpecification");
            }
        }

        return errorMessages;
    }

    private List<String> validateScaleValuesBelongToAxisHierarchy(ValidateEntityUpdateRequest request, ExecutionContext executionContext) {
        List<String> errorMessages = new ArrayList<>();

        try {
            // Obținem maparea axă -> top class din configurație
            List<PostcoordinationAxisToGenericScale> axisToGenericScales = axisToGenericScaleRepository.getPostCoordAxisToGenericScale();
            Map<String, String> axisToTopClassMap = axisToGenericScales.stream()
                    .collect(Collectors.toMap(
                            PostcoordinationAxisToGenericScale::getPostcoordinationAxis,
                            PostcoordinationAxisToGenericScale::getGenericPostcoordinationScaleTopClass
                    ));

            // Construim map-ul hierarchyRootsToEntities
            // Key = top class IRI pentru axă, Value = lista de scale values pentru acea axă
            Map<IRI, List<IRI>> hierarchyRootsToEntities = new HashMap<>();

            for (PostCoordinationScaleCustomization customization : request.entityCustomScaleValues().scaleCustomizations()) {
                String axis = customization.getPostcoordinationAxis();
                if (axis == null) {
                    continue;
                }

                String topClassIri = axisToTopClassMap.get(axis);
                if (topClassIri == null) {
                    LOGGER.warn("No top class found for axis: {}", axis);
                    continue;
                }

                IRI topClassIRI = IRI.create(topClassIri);
                
                // Convertim scale values din String în IRI
                List<IRI> scaleValueIris = customization.getPostcoordinationScaleValues().stream()
                        .filter(iriString -> iriString != null && !iriString.isEmpty())
                        .map(IRI::create)
                        .collect(Collectors.toList());

                if (!scaleValueIris.isEmpty()) {
                    // Grupăm scale values pe top class (axă)
                    hierarchyRootsToEntities.computeIfAbsent(topClassIRI, k -> new ArrayList<>())
                            .addAll(scaleValueIris);
                }
            }

            // Dacă nu avem scale values, nu mai continuăm
            if (hierarchyRootsToEntities.isEmpty()) {
                return errorMessages;
            }

            // Trimitem request-ul către backend pentru validare
            ValidateAxisBelongsToHierarchyResult result = validateAxisBelongsToHierarchyExecutor.execute(
                    new ValidateAxisBelongsToHierarchyAction(request.projectId(), hierarchyRootsToEntities),
                    executionContext
            ).get(15, TimeUnit.SECONDS);

            // Procesăm răspunsul și adăugăm erori pentru entitățile invalide
            for (Map.Entry<IRI, List<IRI>> entry : result.invalidEntitiesByRoot().entrySet()) {
                IRI topClassIRI = entry.getKey();
                List<IRI> invalidEntities = entry.getValue();

                // Găsim axa corespunzătoare acestui top class
                String axis = axisToGenericScales.stream()
                        .filter(scale -> scale.getGenericPostcoordinationScaleTopClass().equals(topClassIRI.toString()))
                        .map(PostcoordinationAxisToGenericScale::getPostcoordinationAxis)
                        .findFirst()
                        .orElse("Unknown");

                for (IRI invalidEntity : invalidEntities) {
                    errorMessages.add("Scale value IRI '" + invalidEntity + "' does not belong to axis '" + axis + "' hierarchy (top class: " + topClassIRI + ")");
                }
            }

        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            LOGGER.error("Error validating scale values belong to axis hierarchy", e);
            errorMessages.add("Error validating scale values belong to axis hierarchy: " + e.getMessage());
        }

        return errorMessages;
    }
}
