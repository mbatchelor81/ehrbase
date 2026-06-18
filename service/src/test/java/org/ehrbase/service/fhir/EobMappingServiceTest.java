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
package org.ehrbase.service.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ehrbase.api.dto.BillingCodeSystemConfig;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.junit.jupiter.api.Test;

class EobMappingServiceTest {

    private static final UUID COMPOSITION_ID = UUID.randomUUID();
    private static final UUID EHR_ID = UUID.randomUUID();

    private final EobMappingService service = new EobMappingService(new BillingCodeSystemConfig(
            List.of("http://hl7.org/fhir/sid/icd-10"), List.of("http://www.ama-assn.org/go/cpt"), List.of()));

    @Test
    void mapToEob_basicFields_populatedCorrectly() {
        Date now = new Date();
        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "test-template", now, List.of());

        assertThat(eob.getId()).isEqualTo(COMPOSITION_ID.toString());
        assertThat(eob.getStatus()).isEqualTo(ExplanationOfBenefitStatus.ACTIVE);
        assertThat(eob.getUse()).isEqualTo(Use.CLAIM);
        assertThat(eob.getPatient().getReference()).isEqualTo("Patient/" + EHR_ID);
        assertThat(eob.getCreated()).isEqualTo(now);
        assertThat(eob.getInsurer().getReference()).isEqualTo("Organization/unknown");
        assertThat(eob.getProvider().getReference()).isEqualTo("Organization/unknown");
        assertThat(eob.getMeta().getProfile()).hasSize(1);
        assertThat(eob.getMeta().getProfile().get(0).getValue())
                .isEqualTo("http://ehrbase.org/fhir/StructureDefinition/eob-from-test-template");
    }

    @Test
    void mapToEob_nullTemplate_noProfile() {
        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, null, new Date(), List.of());

        assertThat(eob.getMeta().getProfile()).isEmpty();
    }

    @Test
    void mapToEob_nullStartTime_usesCurrentDate() {
        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", null, List.of());

        assertThat(eob.getCreated()).isNotNull();
    }

    @Test
    void mapToEob_diagnosisCoding_classifiedAsDiagnosis() {
        List<Map<String, String>> codings =
                List.of(Map.of("system", "http://hl7.org/fhir/sid/icd-10", "code", "J45.0", "display", "Asthma"));

        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), codings);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getDiagnosis().get(0).getSequence()).isEqualTo(1);
        assertThat(eob.getDiagnosis()
                        .get(0)
                        .getDiagnosisCodeableConcept()
                        .getCodingFirstRep()
                        .getCode())
                .isEqualTo("J45.0");
        assertThat(eob.getItem()).hasSize(1);
        assertThat(eob.getProcedure()).isEmpty();
    }

    @Test
    void mapToEob_procedureCoding_classifiedAsProcedure() {
        List<Map<String, String>> codings =
                List.of(Map.of("system", "http://www.ama-assn.org/go/cpt", "code", "99213", "display", "Office Visit"));

        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), codings);

        assertThat(eob.getProcedure()).hasSize(1);
        assertThat(eob.getProcedure()
                        .get(0)
                        .getProcedureCodeableConcept()
                        .getCodingFirstRep()
                        .getCode())
                .isEqualTo("99213");
        assertThat(eob.getDiagnosis()).isEmpty();
        assertThat(eob.getItem()).hasSize(1);
    }

    @Test
    void mapToEob_mixedCodings_classifiedCorrectly() {
        List<Map<String, String>> codings = List.of(
                Map.of("system", "http://hl7.org/fhir/sid/icd-10", "code", "E11", "display", "Diabetes"),
                Map.of("system", "http://www.ama-assn.org/go/cpt", "code", "99214", "display", "Extended Visit"),
                Map.of("system", "http://unknown.org", "code", "XYZ", "display", "Unknown"));

        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), codings);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getProcedure()).hasSize(1);
        assertThat(eob.getItem()).hasSize(3);
    }

    @Test
    void mapToEob_nullCodings_noError() {
        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), null);

        assertThat(eob.getDiagnosis()).isEmpty();
        assertThat(eob.getProcedure()).isEmpty();
        assertThat(eob.getItem()).isEmpty();
    }

    @Test
    void mapToEob_codingWithNullSystemOrCode_skipped() {
        List<Map<String, String>> codings = List.of(Map.of("display", "Missing system and code"));

        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), codings);

        assertThat(eob.getDiagnosis()).isEmpty();
        assertThat(eob.getProcedure()).isEmpty();
        assertThat(eob.getItem()).isEmpty();
    }

    @Test
    void mapToEob_typeField_isProfessional() {
        ExplanationOfBenefit eob = service.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), List.of());

        assertThat(eob.getType().getCodingFirstRep().getSystem())
                .isEqualTo("http://terminology.hl7.org/CodeSystem/claim-type");
        assertThat(eob.getType().getCodingFirstRep().getCode()).isEqualTo("professional");
    }

    @Test
    void mapToEob_customBillingProfile_usesConfiguredSystems() {
        EobMappingService customService = new EobMappingService(new BillingCodeSystemConfig(
                List.of("http://custom.org/diagnosis"), List.of("http://custom.org/procedure"), List.of()));

        List<Map<String, String>> codings = List.of(
                Map.of("system", "http://custom.org/diagnosis", "code", "D1", "display", "Custom Diag"),
                Map.of("system", "http://custom.org/procedure", "code", "P1", "display", "Custom Proc"));

        ExplanationOfBenefit eob = customService.mapToEob(COMPOSITION_ID, EHR_ID, "t", new Date(), codings);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getProcedure()).hasSize(1);
    }
}
