package edu.stanford.protege.webprotege.postcoordinationservice.mappers;

import edu.stanford.protege.webprotege.postcoordinationservice.dto.*;
import edu.stanford.protege.webprotege.postcoordinationservice.events.*;
import edu.stanford.protege.webprotege.postcoordinationservice.model.*;

import java.util.*;
import java.util.stream.Collectors;


public class SpecificationToEventsMapper {


    public static List<PostCoordinationSpecificationEvent> convertFromSpecification(PostCoordinationSpecification specification, Set<String> allowedAxis) {
        List<PostCoordinationSpecificationEvent> response = new ArrayList<>();

        response.addAll(specification.getAllowedAxes().stream().filter(allowedAxis::contains).map(axis -> new AddToAllowedAxisEvent(axis, specification.getLinearizationView())).toList());
        response.addAll(specification.getDefaultAxes().stream().filter(allowedAxis::contains).map(axis -> new AddToDefaultAxisEvent(axis, specification.getLinearizationView())).toList());
        response.addAll(specification.getRequiredAxes().stream().filter(allowedAxis::contains).map(axis -> new AddToRequiredAxisEvent(axis, specification.getLinearizationView())).toList());
        response.addAll(specification.getNotAllowedAxes().stream().filter(allowedAxis::contains).map(axis -> new AddToNotAllowedAxisEvent(axis, specification.getLinearizationView())).toList());

        return response;
    }

    public static Set<PostCoordinationCustomScalesValueEvent> convertToFirstImportEvents(WhoficCustomScalesValues whoficCustomScalesValues) {
        Set<PostCoordinationCustomScalesValueEvent> response = new HashSet<>();

        for (PostCoordinationScaleCustomization request : whoficCustomScalesValues.scaleCustomizations()) {
            response.addAll(request.getPostcoordinationScaleValues()
                    .stream()
                    .map(scaleValue -> new AddCustomScaleValueEvent(request.getPostcoordinationAxis(), scaleValue)).toList());
        }

        return response;
    }


    public static Set<PostCoordinationViewEvent> createEventsFromDiff(WhoficEntityPostCoordinationSpecification existingSpecification, WhoficEntityPostCoordinationSpecification newSpecification) {

        Set<PostCoordinationViewEvent> response = new HashSet<>();

        for (PostCoordinationSpecification spec : newSpecification.postcoordinationSpecifications()) {
            List<PostCoordinationSpecificationEvent> events = new ArrayList<>();
            Optional<PostCoordinationSpecification> oldSpec = existingSpecification.postcoordinationSpecifications().stream()
                    .filter(s -> s.getLinearizationView().equalsIgnoreCase(spec.getLinearizationView()))
                    .findFirst();

            if (oldSpec.isPresent()) {

                List<String> newAllowedAxis = new ArrayList<>(spec.getAllowedAxes());
                newAllowedAxis.removeAll(oldSpec.get().getAllowedAxes());
                events.addAll(newAllowedAxis.stream().map(axis -> new AddToAllowedAxisEvent(axis, spec.getLinearizationView())).toList());

                List<String> newNotAllowedAxis = new ArrayList<>(spec.getNotAllowedAxes());
                newNotAllowedAxis.removeAll(oldSpec.get().getNotAllowedAxes());
                events.addAll(newNotAllowedAxis.stream().map(axis -> new AddToNotAllowedAxisEvent(axis, spec.getLinearizationView())).toList());

                List<String> newDefaultAxis = new ArrayList<>(spec.getDefaultAxes());
                newDefaultAxis.removeAll(oldSpec.get().getDefaultAxes());
                events.addAll(newDefaultAxis.stream().map(axis -> new AddToDefaultAxisEvent(axis, spec.getLinearizationView())).toList());

                List<String> newRequiredAxis = new ArrayList<>(spec.getRequiredAxes());
                newRequiredAxis.removeAll(oldSpec.get().getRequiredAxes());
                events.addAll(newRequiredAxis.stream().map(axis -> new AddToRequiredAxisEvent(axis, spec.getLinearizationView())).toList());
            } else {
                events.addAll(spec.getAllowedAxes().stream().map(axis -> new AddToAllowedAxisEvent(axis, spec.getLinearizationView())).toList());
                events.addAll(spec.getNotAllowedAxes().stream().map(axis -> new AddToNotAllowedAxisEvent(axis, spec.getLinearizationView())).toList());
                events.addAll(spec.getRequiredAxes().stream().map(axis -> new AddToRequiredAxisEvent(axis, spec.getLinearizationView())).toList());
                events.addAll(spec.getDefaultAxes().stream().map(axis -> new AddToDefaultAxisEvent(axis, spec.getLinearizationView())).toList());
            }

            if (!events.isEmpty()) {
                response.add(new PostCoordinationViewEvent(spec.getLinearizationView(), events));
            }
        }


        return response;
    }

    public static Set<PostCoordinationCustomScalesValueEvent> createScaleEventsFromDiff(WhoficCustomScalesValues oldScales, WhoficCustomScalesValues newScales) {
        Set<PostCoordinationCustomScalesValueEvent> events = new HashSet<>();

        // Handle null cases
        if (oldScales == null || oldScales.scaleCustomizations() == null) {
            oldScales = new WhoficCustomScalesValues(null, new ArrayList<>());
        }
        if (newScales == null || newScales.scaleCustomizations() == null) {
            newScales = new WhoficCustomScalesValues(null, new ArrayList<>());
        }

        // Create maps for efficient lookup: normalized axis (lowercase) -> set of scale values
        // Also track original axis names for case-insensitive matching
        Map<String, Set<String>> oldScalesMap = new HashMap<>();
        Map<String, Set<String>> newScalesMap = new HashMap<>();
        Map<String, String> normalizedToOriginalAxis = new HashMap<>();

        // Populate old scales map (using case-insensitive key matching)
        for (PostCoordinationScaleCustomization customization : oldScales.scaleCustomizations()) {
            if (customization != null && customization.getPostcoordinationAxis() != null) {
                String axis = customization.getPostcoordinationAxis();
                String normalizedAxis = axis.toLowerCase();
                Set<String> values = new HashSet<>(customization.getPostcoordinationScaleValues() != null 
                    ? customization.getPostcoordinationScaleValues() 
                    : new ArrayList<>());
                oldScalesMap.put(normalizedAxis, values);
                normalizedToOriginalAxis.put(normalizedAxis, axis);
            }
        }

        // Populate new scales map (using case-insensitive key matching)
        for (PostCoordinationScaleCustomization customization : newScales.scaleCustomizations()) {
            if (customization != null && customization.getPostcoordinationAxis() != null) {
                String axis = customization.getPostcoordinationAxis();
                String normalizedAxis = axis.toLowerCase();
                Set<String> values = new HashSet<>(customization.getPostcoordinationScaleValues() != null 
                    ? customization.getPostcoordinationScaleValues() 
                    : new ArrayList<>());
                newScalesMap.put(normalizedAxis, values);
                // Prefer axis name from newScales if both exist
                normalizedToOriginalAxis.put(normalizedAxis, axis);
            }
        }

        // Collect all unique axes (union of old and new axes)
        Set<String> allNormalizedAxes = new HashSet<>();
        allNormalizedAxes.addAll(oldScalesMap.keySet());
        allNormalizedAxes.addAll(newScalesMap.keySet());

        // Process each axis to generate events
        for (String normalizedAxis : allNormalizedAxes) {
            String axis = normalizedToOriginalAxis.get(normalizedAxis);
            Set<String> oldValues = oldScalesMap.getOrDefault(normalizedAxis, new HashSet<>());
            Set<String> newValues = newScalesMap.getOrDefault(normalizedAxis, new HashSet<>());

            // Values in newScales but not in oldScales → AddCustomScaleValueEvent
            Set<String> valuesToAdd = new HashSet<>(newValues);
            valuesToAdd.removeAll(oldValues);
            for (String scaleValue : valuesToAdd) {
                events.add(new AddCustomScaleValueEvent(axis, scaleValue));
            }

            // Values in oldScales but not in newScales → RemoveCustomScaleValueEvent
            Set<String> valuesToRemove = new HashSet<>(oldValues);
            valuesToRemove.removeAll(newValues);
            for (String scaleValue : valuesToRemove) {
                events.add(new RemoveCustomScaleValueEvent(axis, scaleValue));
            }
        }

        return events;
    }

    public static Map<String, List<PostCoordinationCustomScalesValueEvent>> groupScaleEventsByAxis(List<PostCoordinationCustomScalesValueEvent> events) {
        return events.stream().collect(Collectors.groupingBy(PostCoordinationCustomScalesValueEvent::getPostCoordinationAxis));
    }
}
