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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.ehr.EhrStatus;
import java.util.List;
import java.util.UUID;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.validation.BillingCodeValidator;
import org.ehrbase.api.service.validation.BillingCodeValidator.ValidationResult;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.query.ResultHolder;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EobMappingServiceTest {

    @Mock
    private AqlQueryService aqlQueryService;

    @Mock
    private EhrService ehrService;

    @Mock
    private BillingCodeValidator billingCodeValidator;

    private EobMappingService eobMappingService;

    private static final UUID TEST_EHR_ID = UUID.fromString("7d44b88c-4199-4bad-97dc-d78268e01398");

    @BeforeEach
    void setUp() {
        eobMappingService = new EobMappingService(aqlQueryService, ehrService, billingCodeValidator);
    }

    @Test
    void mapEobForEhr_returnsEmptyList_whenEhrNotQueryable() {
        EhrStatus status = mock(EhrStatus.class);
        when(status.isQueryable()).thenReturn(false);
        when(ehrService.getEhrStatus(TEST_EHR_ID)).thenReturn(status);

        List<ExplanationOfBenefit> result = eobMappingService.mapEobForEhr(TEST_EHR_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void mapEobForEhr_returnsEmptyList_whenNoData() {
        EhrStatus status = mock(EhrStatus.class);
        when(status.isQueryable()).thenReturn(true);
        when(ehrService.getEhrStatus(TEST_EHR_ID)).thenReturn(status);

        QueryResultDto emptyResult = new QueryResultDto();
        emptyResult.setResultSet(List.of());
        when(aqlQueryService.query(any())).thenReturn(emptyResult);

        List<ExplanationOfBenefit> result = eobMappingService.mapEobForEhr(TEST_EHR_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void mapEobForEhr_mapsDiagnosesAndProcedures() {
        EhrStatus status = mock(EhrStatus.class);
        when(status.isQueryable()).thenReturn(true);
        when(ehrService.getEhrStatus(TEST_EHR_ID)).thenReturn(status);
        when(billingCodeValidator.validate(any(), any())).thenReturn(ValidationResult.ok());

        QueryResultDto diagnosisResult = buildDiagnosisResult();
        QueryResultDto procedureResult = buildProcedureResult();
        QueryResultDto encounterResult = buildEncounterResult();

        when(aqlQueryService.query(any()))
                .thenReturn(diagnosisResult)
                .thenReturn(procedureResult)
                .thenReturn(encounterResult);

        List<ExplanationOfBenefit> result = eobMappingService.mapEobForEhr(TEST_EHR_ID);

        assertThat(result).hasSize(1);
        ExplanationOfBenefit eob = result.getFirst();

        assertThat(eob.getStatus()).isEqualTo(ExplanationOfBenefit.ExplanationOfBenefitStatus.ACTIVE);
        assertThat(eob.getUse()).isEqualTo(ExplanationOfBenefit.Use.CLAIM);
        assertThat(eob.getPatient().getReference()).isEqualTo("Patient/" + TEST_EHR_ID);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getDiagnosis()
                        .getFirst()
                        .getDiagnosisCodeableConcept()
                        .getCodingFirstRep()
                        .getCode())
                .isEqualTo("J06.9");
        assertThat(eob.getDiagnosis()
                        .getFirst()
                        .getDiagnosisCodeableConcept()
                        .getCodingFirstRep()
                        .getSystem())
                .isEqualTo(BillingCodeValidator.ICD10_SYSTEM);

        assertThat(eob.getItem()).hasSize(1);
        assertThat(eob.getItem()
                        .getFirst()
                        .getProductOrService()
                        .getCodingFirstRep()
                        .getCode())
                .isEqualTo("99213");
        assertThat(eob.getItem()
                        .getFirst()
                        .getProductOrService()
                        .getCodingFirstRep()
                        .getSystem())
                .isEqualTo(BillingCodeValidator.CPT_SYSTEM);
    }

    @Test
    void mapEobForEhr_throwsOnInvalidBillingCode() {
        EhrStatus status = mock(EhrStatus.class);
        when(status.isQueryable()).thenReturn(true);
        when(ehrService.getEhrStatus(TEST_EHR_ID)).thenReturn(status);
        when(billingCodeValidator.validate(any(), any())).thenReturn(ValidationResult.invalid("Unknown code"));

        QueryResultDto diagnosisResult = buildDiagnosisResult();
        QueryResultDto emptyResult = new QueryResultDto();
        emptyResult.setResultSet(List.of());

        when(aqlQueryService.query(any()))
                .thenReturn(diagnosisResult)
                .thenReturn(emptyResult)
                .thenReturn(emptyResult);

        assertThatThrownBy(() -> eobMappingService.mapEobForEhr(TEST_EHR_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Billing code validation failed");
    }

    @Test
    void mapEobForEhr_includesEncounterData() {
        EhrStatus status = mock(EhrStatus.class);
        when(status.isQueryable()).thenReturn(true);
        when(ehrService.getEhrStatus(TEST_EHR_ID)).thenReturn(status);

        QueryResultDto emptyResult = new QueryResultDto();
        emptyResult.setResultSet(List.of());
        QueryResultDto encounterResult = buildEncounterResult();

        when(aqlQueryService.query(any()))
                .thenReturn(emptyResult)
                .thenReturn(emptyResult)
                .thenReturn(encounterResult);

        List<ExplanationOfBenefit> result = eobMappingService.mapEobForEhr(TEST_EHR_ID);

        assertThat(result).hasSize(1);
        ExplanationOfBenefit eob = result.getFirst();
        assertThat(eob.getFacility().getDisplay()).isEqualTo("General Hospital");
        assertThat(eob.getBillablePeriod()).isNotNull();
        assertThat(eob.getId()).isEqualTo("comp-uid-001");
    }

    @Test
    void resolveCodeSystem_mapsKnownSystems() {
        assertThat(EobMappingService.resolveCodeSystem("ICD10")).isEqualTo(BillingCodeValidator.ICD10_SYSTEM);
        assertThat(EobMappingService.resolveCodeSystem("ICD-10")).isEqualTo(BillingCodeValidator.ICD10_SYSTEM);
        assertThat(EobMappingService.resolveCodeSystem("CPT")).isEqualTo(BillingCodeValidator.CPT_SYSTEM);
        assertThat(EobMappingService.resolveCodeSystem("HCPCS")).isEqualTo(BillingCodeValidator.HCPCS_SYSTEM);
        assertThat(EobMappingService.resolveCodeSystem("SNOMED-CT")).isEqualTo(BillingCodeValidator.SNOMED_SYSTEM);
        assertThat(EobMappingService.resolveCodeSystem("custom-system")).isEqualTo("custom-system");
        assertThat(EobMappingService.resolveCodeSystem(null))
                .isEqualTo("http://terminology.hl7.org/CodeSystem/data-absent-reason");
    }

    private QueryResultDto buildDiagnosisResult() {
        QueryResultDto result = new QueryResultDto();
        ResultHolder row = new ResultHolder();
        row.putResult("ehr_id", TEST_EHR_ID.toString());
        row.putResult("diagnosis", "Acute upper respiratory infection");
        row.putResult("icd10_code", "J06.9");
        row.putResult("coding_system", "ICD10");
        row.putResult("date_of_onset", "2024-01-15");
        row.putResult("clinical_setting", "primary care");
        result.setResultSet(List.of(row));
        return result;
    }

    private QueryResultDto buildProcedureResult() {
        QueryResultDto result = new QueryResultDto();
        ResultHolder row = new ResultHolder();
        row.putResult("ehr_id", TEST_EHR_ID.toString());
        row.putResult("procedure_name", "Office visit");
        row.putResult("procedure_code", "99213");
        row.putResult("code_system", "CPT");
        row.putResult("date_of_service", "2024-01-15");
        result.setResultSet(List.of(row));
        return result;
    }

    private QueryResultDto buildEncounterResult() {
        QueryResultDto result = new QueryResultDto();
        ResultHolder row = new ResultHolder();
        row.putResult("ehr_id", TEST_EHR_ID.toString());
        row.putResult("admission_date", "2024-01-15T08:00:00");
        row.putResult("discharge_date", "2024-01-15T17:00:00");
        row.putResult("encounter_type", "primary care");
        row.putResult("facility_name", "General Hospital");
        row.putResult("attending_provider", "Dr. Smith");
        row.putResult("composition_uid", "comp-uid-001");
        result.setResultSet(List.of(row));
        return result;
    }
}
