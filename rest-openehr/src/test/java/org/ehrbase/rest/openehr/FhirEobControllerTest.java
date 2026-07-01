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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class FhirEobControllerTest {

    private static final UUID TEST_EHR_ID = UUID.fromString("7d44b88c-4199-4bad-97dc-d78268e01398");

    private static final String SAMPLE_EOB_JSON =
            "{\"resourceType\":\"ExplanationOfBenefit\",\"status\":\"active\",\"use\":\"claim\","
                    + "\"patient\":{\"reference\":\"Patient/" + TEST_EHR_ID + "\"}}";

    private final FhirEobService mockEobService = mock();
    private final EhrService mockEhrService = mock();
    private final FhirEobController spyController = spy(new FhirEobController(mockEobService, mockEhrService));

    @BeforeEach
    void setUp() {
        Mockito.reset(mockEobService, mockEhrService, spyController);
        doReturn("http://localhost:8080/ehrbase/rest").when(spyController).getContextPath();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/ehrbase/rest/openehr/v1/fhir/r4/ExplanationOfBenefit");
        request.setQueryString("patient=" + TEST_EHR_ID);
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setScheme("http");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getExplanationOfBenefit_returnsOkWithFhirJson() {
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID)).thenReturn(List.of(SAMPLE_EOB_JSON));

        ResponseEntity<String> response =
                spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/fhir+json");
        assertThat(response.getBody()).contains("\"resourceType\":\"Bundle\"");
        assertThat(response.getBody()).contains("\"total\":1");
        assertThat(response.getBody()).contains("ExplanationOfBenefit");

        verify(mockEhrService).checkEhrExists(TEST_EHR_ID);
    }

    @Test
    void getExplanationOfBenefit_returnsEmptyBundle_whenNoData() {
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID)).thenReturn(List.of());

        ResponseEntity<String> response =
                spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"total\":0");
    }

    @Test
    void getExplanationOfBenefit_returnsBadRequest_onValidationFailure() {
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID))
                .thenThrow(new IllegalArgumentException("Billing code validation failed: bad code"));

        ResponseEntity<String> response =
                spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("OperationOutcome");
        assertThat(response.getBody()).contains("Billing code validation failed");
    }

    @Test
    void getExplanationOfBenefit_throwsOnInvalidPatientId() {
        try {
            spyController.getExplanationOfBenefit("not-a-uuid", null, null, null);
            assertThat(false).as("Expected exception").isTrue();
        } catch (InvalidApiParameterException e) {
            assertThat(e.getMessage()).contains("Invalid patient ID format");
        }
    }

    @Test
    void getExplanationOfBenefit_throwsOnNonexistentEhr() {
        doThrow(new ObjectNotFoundException("ehr", "EHR not found"))
                .when(mockEhrService)
                .checkEhrExists(TEST_EHR_ID);

        try {
            spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), null, null, null);
            assertThat(false).as("Expected exception").isTrue();
        } catch (ObjectNotFoundException e) {
            assertThat(e.getMessage()).contains("EHR not found");
        }
    }

    @Test
    void getExplanationOfBenefit_supportsPagination() {
        List<String> eobs = List.of(SAMPLE_EOB_JSON, SAMPLE_EOB_JSON, SAMPLE_EOB_JSON);
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID)).thenReturn(eobs);

        ResponseEntity<String> response = spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), 2, 0, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"total\":3");
        assertThat(response.getBody()).contains("\"relation\":\"next\"");
    }

    @Test
    void getExplanationOfBenefit_secondPageHasPreviousLink() {
        List<String> eobs = List.of(SAMPLE_EOB_JSON, SAMPLE_EOB_JSON, SAMPLE_EOB_JSON);
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID)).thenReturn(eobs);

        ResponseEntity<String> response = spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), 2, 2, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"relation\":\"previous\"");
    }

    @Test
    void getExplanationOfBenefit_contentTypeIsFhirJson() {
        when(mockEobService.getEobJsonForEhr(TEST_EHR_ID)).thenReturn(List.of(SAMPLE_EOB_JSON));

        ResponseEntity<String> response =
                spyController.getExplanationOfBenefit(TEST_EHR_ID.toString(), null, null, "application/fhir+json");

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/fhir+json");
    }
}
