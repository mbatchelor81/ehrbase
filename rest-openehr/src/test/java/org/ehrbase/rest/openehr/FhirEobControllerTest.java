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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.EobMappingService;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FhirEobControllerTest {

    private final EobMappingService mockEobMappingService = mock();

    private FhirEobController controller;

    @BeforeEach
    void setUp() {
        controller = new FhirEobController(mockEobMappingService);
    }

    @Test
    void getExplanationOfBenefit_returnsBundle() {
        UUID ehrId = UUID.fromString("a6ddec4c-a68a-49ef-963e-3e0bc1970a28");
        ExplanationOfBenefit eob = createTestEob(ehrId);

        when(mockEobMappingService.getExplanationOfBenefits(eq(ehrId), eq(0), anyInt()))
                .thenReturn(List.of(eob));

        ResponseEntity<String> response = controller.getExplanationOfBenefit(ehrId.toString(), 0, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/fhir+json");
        assertThat(response.getBody()).contains("\"resourceType\": \"Bundle\"");
        assertThat(response.getBody()).contains("ExplanationOfBenefit");
    }

    @Test
    void getExplanationOfBenefit_acceptsPatientPrefix() {
        UUID ehrId = UUID.fromString("a6ddec4c-a68a-49ef-963e-3e0bc1970a28");

        when(mockEobMappingService.getExplanationOfBenefits(eq(ehrId), eq(0), anyInt()))
                .thenReturn(List.of());

        ResponseEntity<String> response = controller.getExplanationOfBenefit("Patient/" + ehrId, 0, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getExplanationOfBenefit_returnsEmptyBundle() {
        UUID ehrId = UUID.fromString("a6ddec4c-a68a-49ef-963e-3e0bc1970a28");

        when(mockEobMappingService.getExplanationOfBenefits(eq(ehrId), eq(0), anyInt()))
                .thenReturn(List.of());

        ResponseEntity<String> response = controller.getExplanationOfBenefit(ehrId.toString(), 0, 10);

        assertThat(response.getBody()).contains("\"total\": 0");
        assertThat(response.getBody()).contains("\"type\": \"searchset\"");
    }

    @Test
    void getExplanationOfBenefit_invalidPatientIdThrows() {
        assertThatThrownBy(() -> controller.getExplanationOfBenefit("not-a-uuid", 0, 10))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessageContaining("Invalid patient ID format");
    }

    @Test
    void getExplanationOfBenefit_clampsPaginationValues() {
        UUID ehrId = UUID.fromString("a6ddec4c-a68a-49ef-963e-3e0bc1970a28");

        when(mockEobMappingService.getExplanationOfBenefits(eq(ehrId), eq(0), eq(100)))
                .thenReturn(List.of());

        // count > MAX_COUNT should be clamped to MAX_COUNT
        ResponseEntity<String> response = controller.getExplanationOfBenefit(ehrId.toString(), -1, 999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ExplanationOfBenefit createTestEob(UUID ehrId) {
        ExplanationOfBenefit eob = new ExplanationOfBenefit();
        eob.setId("test-eob-1");
        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        eob.setUse(Use.CLAIM);
        eob.setPatient(new Reference("Patient/" + ehrId));
        return eob;
    }
}
