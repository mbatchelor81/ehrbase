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
package org.ehrbase.rest.openehr;

import ca.uhn.fhir.context.FhirContext;
import java.util.Objects;
import java.util.UUID;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.EobMappingService;
import org.ehrbase.api.service.EobMappingService.EobResult;
import org.ehrbase.rest.BaseController;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 ExplanationOfBenefit endpoint that maps openEHR composition data
 * to FHIR EOB resources.
 */
@RestController
@RequestMapping(path = "${openehr-api.context-path:/rest/openehr}/fhir/r4")
public class FhirEobController extends BaseController {

    private static final String APPLICATION_FHIR_JSON = "application/fhir+json";
    private static final int MAX_COUNT = 100;

    private final EobMappingService eobMappingService;
    private final FhirContext fhirContext;

    @Autowired
    public FhirEobController(EobMappingService eobMappingService) {
        this.eobMappingService = Objects.requireNonNull(eobMappingService);
        this.fhirContext = FhirContext.forR4();
    }

    @GetMapping(
            value = "/ExplanationOfBenefit",
            produces = {APPLICATION_FHIR_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> getExplanationOfBenefit(
            @RequestParam(value = "patient") String patientId,
            @RequestParam(value = "_offset", required = false, defaultValue = "0") int offset,
            @RequestParam(value = "_count", required = false, defaultValue = "10") int count) {

        UUID ehrId = parsePatientId(patientId);

        int effectiveCount = Math.max(0, Math.min(count, MAX_COUNT));
        int effectiveOffset = Math.max(0, offset);

        EobResult result = eobMappingService.getExplanationOfBenefits(ehrId, effectiveOffset, effectiveCount);

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.total());

        for (ExplanationOfBenefit eob : result.eobList()) {
            Bundle.BundleEntryComponent entry = new Bundle.BundleEntryComponent();
            entry.setResource(eob);
            if (eob.getId() != null) {
                entry.setFullUrl("ExplanationOfBenefit/" + eob.getId());
            }
            bundle.addEntry(entry);
        }

        String responseBody = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, APPLICATION_FHIR_JSON);

        return ResponseEntity.ok().headers(headers).body(responseBody);
    }

    private UUID parsePatientId(String patientId) {
        String id = patientId;
        if (id.startsWith("Patient/")) {
            id = id.substring("Patient/".length());
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidApiParameterException("Invalid patient ID format: " + patientId);
        }
    }
}
