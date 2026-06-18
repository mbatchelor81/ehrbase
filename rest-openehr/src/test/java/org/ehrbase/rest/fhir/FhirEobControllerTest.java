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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import java.util.Date;
import java.util.UUID;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleLinkComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class FhirEobControllerTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String BASE_URL = "https://test.ehrbase/rest/fhir/r4";

    private final FhirEobService mockService = mock();
    private final FhirEobController controller = spy(new FhirEobController(mockService));

    @BeforeEach
    void setUp() {
        Mockito.reset(mockService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("test.ehrbase");
        request.setContextPath("");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void getExplanationOfBenefit_validPatient_returnsBundle() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/fhir+json"));

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"resourceType\":\"Bundle\"");
        assertThat(body).contains("\"type\":\"searchset\"");
    }

    @Test
    void getExplanationOfBenefit_patientWithPrefix_stripsPrefix() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        controller.getExplanationOfBenefit("Patient/" + PATIENT_ID, 0, 20);

        verify(mockService).getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString());
    }

    @Test
    void getExplanationOfBenefit_invalidPatientId_throwsException() {
        assertThatThrownBy(() -> controller.getExplanationOfBenefit("not-a-uuid", 0, 20))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessageContaining("Invalid patient identifier");
    }

    @Test
    void getExplanationOfBenefit_negativeOffset_throwsException() {
        assertThatThrownBy(() -> controller.getExplanationOfBenefit(PATIENT_ID.toString(), -1, 20))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessageContaining("_offset must not be negative");
    }

    @Test
    void getExplanationOfBenefit_emptyBundle_returnsEmptySearchset() {
        Bundle emptyBundle = new Bundle();
        emptyBundle.setType(BundleType.SEARCHSET);
        emptyBundle.setTotal(0);

        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(emptyBundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).contains("\"total\":0");
    }

    @Test
    void getExplanationOfBenefit_bundleWithPagination_containsNextLink() {
        Bundle bundle = createTestBundle();
        BundleLinkComponent nextLink = new BundleLinkComponent();
        nextLink.setRelation("next");
        nextLink.setUrl(BASE_URL + "/ExplanationOfBenefit?patient=" + PATIENT_ID + "&_offset=20&_count=20");
        bundle.addLink(nextLink);

        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        String body = response.getBody();
        assertThat(body).contains("\"relation\":\"next\"");
        assertThat(body).contains("_offset=20");
    }

    @Test
    void getExplanationOfBenefit_contentType_isFhirJson() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/fhir+json"));
    }

    @Test
    void getExplanationOfBenefit_bundleContainsEobResource() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        String body = response.getBody();
        assertThat(body).contains("\"resourceType\":\"ExplanationOfBenefit\"");
        assertThat(body).contains("\"status\":\"active\"");
        assertThat(body).contains("Patient/" + PATIENT_ID);
    }

    @Test
    void getExplanationOfBenefit_countExceedsMax_clampedTo100() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), eq(100), anyString()))
                .thenReturn(bundle);

        controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 500);

        verify(mockService).getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), eq(100), anyString());
    }

    @Test
    void getExplanationOfBenefit_validFhirBundleStructure() {
        Bundle bundle = createTestBundle();
        when(mockService.getExplanationOfBenefitForPatient(eq(PATIENT_ID), eq(0), anyInt(), anyString()))
                .thenReturn(bundle);

        ResponseEntity<String> response = controller.getExplanationOfBenefit(PATIENT_ID.toString(), 0, 20);

        String body = response.getBody();
        FhirContext ctx = FhirContext.forR4();
        Bundle parsed = ctx.newJsonParser().parseResource(Bundle.class, body);

        assertThat(parsed.getType()).isEqualTo(BundleType.SEARCHSET);
        assertThat(parsed.getEntry()).hasSize(1);
        assertThat(parsed.getEntry().get(0).getResource()).isInstanceOf(ExplanationOfBenefit.class);
    }

    private Bundle createTestBundle() {
        ExplanationOfBenefit eob = new ExplanationOfBenefit();
        eob.setId(UUID.randomUUID().toString());
        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        eob.setUse(Use.CLAIM);
        eob.setPatient(new Reference("Patient/" + PATIENT_ID));
        eob.setCreated(new Date());
        eob.setInsurer(new Reference("Organization/unknown"));
        eob.setProvider(new Reference("Organization/unknown"));

        Bundle bundle = new Bundle();
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTotal(1);

        BundleEntryComponent entry = new BundleEntryComponent();
        entry.setResource(eob);
        entry.setFullUrl(BASE_URL + "/ExplanationOfBenefit/" + eob.getId());
        bundle.addEntry(entry);

        return bundle;
    }
}
