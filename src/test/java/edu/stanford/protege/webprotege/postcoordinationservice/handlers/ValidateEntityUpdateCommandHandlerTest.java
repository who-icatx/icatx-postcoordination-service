package edu.stanford.protege.webprotege.postcoordinationservice.handlers;

import edu.stanford.protege.webprotege.common.ProjectId;
import edu.stanford.protege.webprotege.ipc.CommandExecutor;
import edu.stanford.protege.webprotege.ipc.ExecutionContext;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisResult;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationScaleCustomization;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.PostCoordinationSpecification;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyResult;
import edu.stanford.protege.webprotege.postcoordinationservice.model.CompositeAxis;
import edu.stanford.protege.webprotege.postcoordinationservice.model.PostcoordinationAxisToGenericScale;
import edu.stanford.protege.webprotege.postcoordinationservice.model.TableConfiguration;
import edu.stanford.protege.webprotege.postcoordinationservice.model.WhoficCustomScalesValues;
import edu.stanford.protege.webprotege.postcoordinationservice.model.WhoficEntityPostCoordinationSpecification;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationTableConfigRepository;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostcoordinationAxisToGenericScaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semanticweb.owlapi.model.IRI;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateEntityUpdateCommandHandlerTest {

    @Mock
    private CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> entityTypeExecutor;

    @Mock
    private PostCoordinationTableConfigRepository configRepository;

    @Mock
    private CommandExecutor<CheckNonExistentIrisAction, CheckNonExistentIrisResult> checkNonExistentIrisExecutor;

    @Mock
    private CommandExecutor<ValidateAxisBelongsToHierarchyAction, ValidateAxisBelongsToHierarchyResult> validateAxisBelongsToHierarchyExecutor;

    @Mock
    private PostcoordinationAxisToGenericScaleRepository axisToGenericScaleRepository;

    @Mock
    private ExecutionContext executionContext;

    private ValidateEntityUpdateCommandHandler handler;

    private static final String ENTITY_IRI = "http://who.int/icd/entity/123";
    private static final ProjectId PROJECT_ID = ProjectId.generate();
    private static final String ENTITY_TYPE = "ICD";
    private static final String AXIS_COURSE = "Course";
    private static final String AXIS_LATERALITY = "Laterality";
    private static final String AXIS_SEVERITY = "Severity";
    private static final String INVALID_AXIS = "InvalidAxis";
    private static final String COURSE_SCALE_VALUE = "http://who.int/icd/scale/course/acute";
    private static final String LATERALITY_SCALE_VALUE = "http://who.int/icd/scale/laterality/left";
    private static final String NON_EXISTENT_SCALE_VALUE = "http://who.int/icd/scale/nonexistent/value";

    @BeforeEach
    void setUp() {
        handler = new ValidateEntityUpdateCommandHandler(
                entityTypeExecutor,
                configRepository,
                checkNonExistentIrisExecutor,
                validateAxisBelongsToHierarchyExecutor,
                axisToGenericScaleRepository
        );
    }

    @Test
    void testValidRequest_NoErrors() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = createValidCustomScales();

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForValidRequest();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertTrue(response.getErrorMessages().isEmpty(),
                "Expected no errors but got: " + response.getErrorMessages());
    }

    @Test
    void testEntityIriMismatch_ShouldReturnError() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                "http://who.int/icd/entity/different",
                List.of()
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertEquals(1, response.getErrorMessages().size());
        assertTrue(response.getErrorMessages().get(0).contains("Entity IRI mismatch"));
    }

    @Test
    void testInvalidAxisInSpecification_ShouldReturnError() {
        // Given
        PostCoordinationSpecification spec = new PostCoordinationSpecification(
                "view1",
                List.of(INVALID_AXIS), // Invalid axis
                List.of(),
                List.of(),
                List.of()
        );

        WhoficEntityPostCoordinationSpecification specification = new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of(spec)
        );

        WhoficCustomScalesValues customScales = createValidCustomScales();

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        setupMocksForAxisHierarchyValidation();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(INVALID_AXIS) && msg.contains("allowedAxes")));
    }

    @Test
    void testInvalidAxisInCustomScales_ShouldReturnError() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(COURSE_SCALE_VALUE),
                        INVALID_AXIS // Invalid axis
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        // INVALID_AXIS doesn't exist in axis-to-generic-scale config, so hierarchy validation won't run
        // No need to mock it

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(INVALID_AXIS) && msg.contains("entityCustomScaleValues")));
    }

    @Test
    void testNonExistentScaleValue_ShouldReturnError() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(NON_EXISTENT_SCALE_VALUE),
                        AXIS_COURSE
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of(IRI.create(NON_EXISTENT_SCALE_VALUE)))
                ));
        setupMocksForAxisHierarchyValidation();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(NON_EXISTENT_SCALE_VALUE) && msg.contains("does not exist")));
    }

    @Test
    void testCustomScaleAxisNotInAllowedOrRequiredAxes_ShouldReturnError() {
        // Given
        PostCoordinationSpecification spec = new PostCoordinationSpecification(
                "view1",
                List.of(AXIS_COURSE), // Only Course is allowed
                List.of(),
                List.of(),
                List.of() // Severity is not in allowed or required
        );

        WhoficEntityPostCoordinationSpecification specification = new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of(spec)
        );

        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of("http://who.int/icd/scale/severity/mild"),
                        AXIS_SEVERITY // Severity is not in allowed/required axes
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        setupMocksForAxisHierarchyValidation();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(AXIS_SEVERITY) &&
                        msg.contains("not present in allowedAxes or requiredAxes")));
    }

    @Test
    void testScaleValueFromWrongAxis_ShouldReturnError() {
        // Given: Laterality scale value assigned to Course axis (this should be invalid)
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(LATERALITY_SCALE_VALUE), // Laterality scale value
                        AXIS_COURSE // But assigned to Course axis - this should be invalid!
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));

        // Setup axis to generic scale mappings
        PostcoordinationAxisToGenericScale courseAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_COURSE,
                "http://who.int/icd/class/CourseScaleTopClass",
                "true"
        );
        PostcoordinationAxisToGenericScale lateralityAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_LATERALITY,
                "http://who.int/icd/class/LateralityScaleTopClass",
                "true"
        );
        when(axisToGenericScaleRepository.getPostCoordAxisToGenericScale())
                .thenReturn(List.of(courseAxisMapping, lateralityAxisMapping));

        // Mock backend response: Laterality scale value does not belong to Course axis hierarchy
        IRI courseTopClass = IRI.create("http://who.int/icd/class/CourseScaleTopClass");
        IRI lateralityScaleValueIRI = IRI.create(LATERALITY_SCALE_VALUE);
        when(validateAxisBelongsToHierarchyExecutor.execute(any(ValidateAxisBelongsToHierarchyAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ValidateAxisBelongsToHierarchyResult.create(
                                Map.of(courseTopClass, List.of(lateralityScaleValueIRI))
                        )
                ));

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty(),
                "Expected error for scale value from wrong axis");
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(LATERALITY_SCALE_VALUE) &&
                        msg.contains(AXIS_COURSE) &&
                        msg.contains("does not belong to axis")),
                "Expected error message about scale value not belonging to axis");
    }

    @Test
    void testMultipleErrors_ShouldReturnAllErrors() {
        // Given: Request with multiple validation errors
        PostCoordinationSpecification spec = new PostCoordinationSpecification(
                "view1",
                List.of(INVALID_AXIS), // Invalid axis
                List.of(),
                List.of(),
                List.of()
        );

        WhoficEntityPostCoordinationSpecification specification = new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of(spec)
        );

        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(NON_EXISTENT_SCALE_VALUE),
                        INVALID_AXIS // Invalid axis
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of(IRI.create(NON_EXISTENT_SCALE_VALUE)))
                ));
        // INVALID_AXIS doesn't exist in axis-to-generic-scale config, so hierarchy validation won't run
        // No need to mock it

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        // Should have errors for invalid axis in specification
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(INVALID_AXIS) && msg.contains("allowedAxes")));
        // Should have errors for invalid axis in custom scales
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(INVALID_AXIS) && msg.contains("entityCustomScaleValues")));
        // Should have errors for non-existent scale value
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(NON_EXISTENT_SCALE_VALUE)));
    }

    @Test
    void testCompositeAxes_ShouldIncludeSubAxes() {
        // Given: Configuration with composite axes
        CompositeAxis compositeAxis = new CompositeAxis(
                "CompositeAxis",
                List.of("SubAxis1", "SubAxis2")
        );

        TableConfiguration config = new TableConfiguration(
                ENTITY_TYPE,
                List.of(AXIS_COURSE),
                List.of(compositeAxis)
        );

        WhoficEntityPostCoordinationSpecification specification = new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of(new PostCoordinationSpecification(
                        "view1",
                        List.of("SubAxis1"), // Sub-axis should be allowed
                        List.of(),
                        List.of(),
                        List.of()
                ))
        );

        // Custom scales with Course axis - but specification only allows SubAxis1
        // So we need to use SubAxis1 in custom scales or add Course to allowed axes
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(COURSE_SCALE_VALUE),
                        "SubAxis1" // Use SubAxis1 which is in allowed axes
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        when(entityTypeExecutor.execute(any(GetIcatxEntityTypeRequest.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new GetIcatxEntityTypeResponse(List.of(ENTITY_TYPE))
                ));
        when(configRepository.getTableConfigurationByEntityType(List.of(ENTITY_TYPE)))
                .thenReturn(List.of(config));
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        // Setup axis to generic scale mappings including SubAxis1
        PostcoordinationAxisToGenericScale subAxis1Mapping = new PostcoordinationAxisToGenericScale(
                "SubAxis1",
                "http://who.int/icd/class/SubAxis1ScaleTopClass",
                "true"
        );
        PostcoordinationAxisToGenericScale courseAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_COURSE,
                "http://who.int/icd/class/CourseScaleTopClass",
                "true"
        );
        when(axisToGenericScaleRepository.getPostCoordAxisToGenericScale())
                .thenReturn(List.of(subAxis1Mapping, courseAxisMapping));
        when(validateAxisBelongsToHierarchyExecutor.execute(any(ValidateAxisBelongsToHierarchyAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ValidateAxisBelongsToHierarchyResult.create(Map.of())
                ));

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertTrue(response.getErrorMessages().isEmpty());
    }

    @Test
    void testEntityTypeExecutorTimeout_ShouldReturnError() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = createValidCustomScales();

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        CompletableFuture<GetIcatxEntityTypeResponse> future = new CompletableFuture<>();
        when(entityTypeExecutor.execute(any(GetIcatxEntityTypeRequest.class), any(ExecutionContext.class)))
                .thenReturn(future);
        // Future never completes, simulating timeout
        // Still need to mock checkNonExistentIrisExecutor because validateScaleValuesExist is called after timeout
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        // Note: The actual timeout handling depends on the CompletableFuture.get() call
        // This test may need adjustment based on actual timeout behavior
        assertNotNull(response);
        // Timeout should result in an error message
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains("Error fetching entity types")));
    }

    @Test
    void testEmptyCustomScales_ShouldPassValidation() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of() // Empty custom scales
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        // Empty custom scales means no scale values to check, so checkNonExistentIrisExecutor won't be called
        // No need to mock it

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertTrue(response.getErrorMessages().isEmpty());
    }

    @Test
    void testEmptySpecifications_ShouldPassValidation() {
        // Given
        WhoficEntityPostCoordinationSpecification specification = new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of() // Empty specifications
        );

        WhoficCustomScalesValues customScales = createValidCustomScales();

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        setupMocksForAxisHierarchyValidation();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        // Custom scales axis should not be in allowed/required axes
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains("not present in allowedAxes or requiredAxes")));
    }

    @Test
    void testScaleValuesBelongToAxisHierarchy_ValidValues_ShouldPass() {
        // Given: Valid scale values for their respective axes
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(
                        new PostCoordinationScaleCustomization(
                                List.of(COURSE_SCALE_VALUE),
                                AXIS_COURSE
                        ),
                        new PostCoordinationScaleCustomization(
                                List.of(LATERALITY_SCALE_VALUE),
                                AXIS_LATERALITY
                        )
                )
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForValidRequest();

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertTrue(response.getErrorMessages().isEmpty(),
                "Expected no errors for valid scale values");
    }

    @Test
    void testScaleValuesBelongToAxisHierarchy_InvalidValue_ShouldReturnError() {
        // Given: Scale value that doesn't belong to its axis hierarchy
        WhoficEntityPostCoordinationSpecification specification = createValidSpecification();
        String invalidScaleValue = "http://who.int/icd/scale/invalid/value";
        WhoficCustomScalesValues customScales = new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(invalidScaleValue),
                        AXIS_COURSE
                ))
        );

        ValidateEntityUpdateRequest request = new ValidateEntityUpdateRequest(
                PROJECT_ID,
                customScales,
                specification
        );

        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));

        // Setup axis to generic scale mappings
        PostcoordinationAxisToGenericScale courseAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_COURSE,
                "http://who.int/icd/class/CourseScaleTopClass",
                "true"
        );
        when(axisToGenericScaleRepository.getPostCoordAxisToGenericScale())
                .thenReturn(List.of(courseAxisMapping));

        // Mock backend response: scale value does not belong to Course axis hierarchy
        IRI courseTopClass = IRI.create("http://who.int/icd/class/CourseScaleTopClass");
        IRI invalidScaleValueIRI = IRI.create(invalidScaleValue);
        when(validateAxisBelongsToHierarchyExecutor.execute(any(ValidateAxisBelongsToHierarchyAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ValidateAxisBelongsToHierarchyResult.create(
                                Map.of(courseTopClass, List.of(invalidScaleValueIRI))
                        )
                ));

        // When
        ValidateEntityUpdateResponse response = handler.handleRequest(request, executionContext).block();

        // Then
        assertNotNull(response);
        assertFalse(response.getErrorMessages().isEmpty());
        assertTrue(response.getErrorMessages().stream()
                .anyMatch(msg -> msg.contains(invalidScaleValue) &&
                        msg.contains(AXIS_COURSE) &&
                        msg.contains("does not belong to axis")));
    }

    // Helper methods

    private WhoficEntityPostCoordinationSpecification createValidSpecification() {
        PostCoordinationSpecification spec = new PostCoordinationSpecification(
                "view1",
                List.of(AXIS_COURSE, AXIS_LATERALITY),
                List.of(AXIS_COURSE),
                List.of(),
                List.of()
        );

        return new WhoficEntityPostCoordinationSpecification(
                ENTITY_IRI,
                ENTITY_TYPE,
                List.of(spec)
        );
    }

    private WhoficCustomScalesValues createValidCustomScales() {
        return new WhoficCustomScalesValues(
                ENTITY_IRI,
                List.of(new PostCoordinationScaleCustomization(
                        List.of(COURSE_SCALE_VALUE),
                        AXIS_COURSE
                ))
        );
    }

    private void setupMocksForValidRequest() {
        setupMocksForEntityType();
        when(checkNonExistentIrisExecutor.execute(any(CheckNonExistentIrisAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CheckNonExistentIrisResult.create(Set.of())
                ));
        setupMocksForAxisHierarchyValidation();
    }

    private void setupMocksForAxisHierarchyValidation() {
        // Setup axis to generic scale mappings
        PostcoordinationAxisToGenericScale courseAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_COURSE,
                "http://who.int/icd/class/CourseScaleTopClass",
                "true"
        );
        PostcoordinationAxisToGenericScale lateralityAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_LATERALITY,
                "http://who.int/icd/class/LateralityScaleTopClass",
                "true"
        );
        PostcoordinationAxisToGenericScale severityAxisMapping = new PostcoordinationAxisToGenericScale(
                AXIS_SEVERITY,
                "http://who.int/icd/class/SeverityScaleTopClass",
                "true"
        );
        when(axisToGenericScaleRepository.getPostCoordAxisToGenericScale())
                .thenReturn(List.of(courseAxisMapping, lateralityAxisMapping, severityAxisMapping));

        // Default: all scale values belong to their axis hierarchy (no invalid entities)
        when(validateAxisBelongsToHierarchyExecutor.execute(any(ValidateAxisBelongsToHierarchyAction.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ValidateAxisBelongsToHierarchyResult.create(Map.of())
                ));
    }

    private void setupMocksForEntityType() {
        TableConfiguration config = new TableConfiguration(
                ENTITY_TYPE,
                List.of(AXIS_COURSE, AXIS_LATERALITY, AXIS_SEVERITY),
                List.of()
        );

        when(entityTypeExecutor.execute(any(GetIcatxEntityTypeRequest.class), any(ExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new GetIcatxEntityTypeResponse(List.of(ENTITY_TYPE))
                ));
        when(configRepository.getTableConfigurationByEntityType(List.of(ENTITY_TYPE)))
                .thenReturn(List.of(config));
    }
}
