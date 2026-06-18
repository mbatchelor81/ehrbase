/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.rest.fhir;

import ca.uhn.fhir.context.FhirContext;
import java.util.UUID;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * FHIR R4 controller for ExplanationOfBenefit resources.
 * Maps openEHR composition data to FHIR EOB format.
 */
@RestController
@RequestMapping(path = "${openehr-api.context-path:/rest}/fhir/r4")
public class FhirEobController {

    public static final String APPLICATION_FHIR_JSON_VALUE = "application/fhir+json";
    public static final MediaType APPLICATION_FHIR_JSON = MediaType.parseMediaType(APPLICATION_FHIR_JSON_VALUE);

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;

    private final FhirEobService fhirEobService;
    private final FhirContext fhirContext;

    public FhirEobController(FhirEobService fhirEobService) {
        this.fhirEobService = fhirEobService;
        this.fhirContext = FhirContext.forR4();
    }

    @GetMapping(value = "/ExplanationOfBenefit", produces = APPLICATION_FHIR_JSON_VALUE)
    public ResponseEntity<String> getExplanationOfBenefit(
            @RequestParam(value = "patient") String patientId,
            @RequestParam(value = "_offset", defaultValue = "0") int offset,
            @RequestParam(value = "_count", defaultValue = "20") int count) {

        UUID ehrId = parsePatientId(patientId);

        if (offset < 0) {
            throw new InvalidApiParameterException("_offset must not be negative");
        }
        int effectiveCount = Math.min(Math.max(count, 1), MAX_COUNT);

        String baseUrl = resolveBaseUrl();
        Bundle bundle = fhirEobService.getExplanationOfBenefitForPatient(ehrId, offset, effectiveCount, baseUrl);

        String json = fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(bundle);

        return ResponseEntity.ok().contentType(APPLICATION_FHIR_JSON).body(json);
    }

    private UUID parsePatientId(String patientId) {
        String id = patientId.startsWith("Patient/") ? patientId.substring("Patient/".length()) : patientId;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidApiParameterException(
                    "Invalid patient identifier: must be a valid UUID, got: " + patientId);
        }
    }

    private String resolveBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/rest/fhir/r4")
                .build()
                .toUriString();
    }
}
