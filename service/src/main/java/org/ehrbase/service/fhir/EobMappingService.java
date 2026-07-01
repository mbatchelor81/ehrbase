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

import ca.uhn.fhir.context.FhirContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.ehrbase.api.service.validation.BillingCodeValidator;
import org.ehrbase.api.service.validation.BillingCodeValidator.ValidationResult;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.DiagnosisComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps openEHR composition data (retrieved via AQL) to FHIR R4 ExplanationOfBenefit resources.
 * Uses the stored billing AQL queries seeded by EM-53.
 */
@Service
public class EobMappingService implements FhirEobService {

    private static final Logger logger = LoggerFactory.getLogger(EobMappingService.class);

    private static final String DIAGNOSES_QUERY = "SELECT e/ehr_id/value AS ehr_id, "
            + "eval/data[at0001]/items[at0002]/value/value AS diagnosis, "
            + "eval/data[at0001]/items[at0002]/value/defining_code/code_string AS icd10_code, "
            + "eval/data[at0001]/items[at0002]/value/defining_code/terminology_id/value AS coding_system, "
            + "eval/data[at0001]/items[at0009]/value/value AS date_of_onset, "
            + "c/context/setting/value AS clinical_setting "
            + "FROM EHR e CONTAINS COMPOSITION c CONTAINS EVALUATION eval "
            + "WHERE e/ehr_id/value = $ehr_id "
            + "ORDER BY eval/data[at0001]/items[at0009]/value/value DESC";

    private static final String PROCEDURES_QUERY = "SELECT e/ehr_id/value AS ehr_id, "
            + "act/description[at0001]/items[at0002]/value/value AS procedure_name, "
            + "act/description[at0001]/items[at0002]/value/defining_code/code_string AS procedure_code, "
            + "act/description[at0001]/items[at0002]/value/defining_code/terminology_id/value AS code_system, "
            + "act/time/value AS date_of_service "
            + "FROM EHR e CONTAINS COMPOSITION c CONTAINS ACTION act "
            + "WHERE e/ehr_id/value = $ehr_id "
            + "ORDER BY act/time/value DESC";

    private static final String ENCOUNTERS_QUERY = "SELECT e/ehr_id/value AS ehr_id, "
            + "c/context/start_time/value AS admission_date, "
            + "c/context/end_time/value AS discharge_date, "
            + "c/context/setting/value AS encounter_type, "
            + "c/context/health_care_facility/name AS facility_name, "
            + "c/context/participations/performer/name AS attending_provider, "
            + "c/uid/value AS composition_uid "
            + "FROM EHR e CONTAINS COMPOSITION c "
            + "WHERE e/ehr_id/value = $ehr_id "
            + "ORDER BY c/context/start_time/value DESC";

    private final AqlQueryService aqlQueryService;
    private final EhrService ehrService;
    private final BillingCodeValidator billingCodeValidator;
    private final FhirContext fhirContext;

    public EobMappingService(
            AqlQueryService aqlQueryService, EhrService ehrService, BillingCodeValidator billingCodeValidator) {
        this.aqlQueryService = Objects.requireNonNull(aqlQueryService);
        this.ehrService = Objects.requireNonNull(ehrService);
        this.billingCodeValidator = Objects.requireNonNull(billingCodeValidator);
        this.fhirContext = FhirContext.forR4();
    }

    @Override
    public List<String> getEobJsonForEhr(UUID ehrId) {
        List<ExplanationOfBenefit> eobs = mapEobForEhr(ehrId);
        return eobs.stream()
                .map(eob -> fhirContext.newJsonParser().encodeResourceToString(eob))
                .toList();
    }

    /**
     * Retrieves and maps openEHR data to FHIR R4 ExplanationOfBenefit resources for a given EHR.
     *
     * @param ehrId the EHR UUID
     * @return list of EOB resources, empty if the EHR is not queryable
     * @throws IllegalArgumentException if billing codes fail validation
     */
    public List<ExplanationOfBenefit> mapEobForEhr(UUID ehrId) {
        if (!isEhrQueryable(ehrId)) {
            return Collections.emptyList();
        }

        Map<String, Object> params = Map.of("ehr_id", ehrId.toString());

        List<DiagnosisRow> diagnoses = queryDiagnoses(params);
        List<ProcedureRow> procedures = queryProcedures(params);
        List<EncounterRow> encounters = queryEncounters(params);

        validateBillingCodes(diagnoses, procedures);

        if (encounters.isEmpty() && diagnoses.isEmpty() && procedures.isEmpty()) {
            return Collections.emptyList();
        }

        return buildEobResources(ehrId, encounters, diagnoses, procedures);
    }

    private boolean isEhrQueryable(UUID ehrId) {
        try {
            var status = ehrService.getEhrStatus(ehrId);
            return status.isQueryable();
        } catch (Exception e) {
            logger.warn("Failed to check EHR queryable status for {}: {}", ehrId, e.getMessage());
            return false;
        }
    }

    private List<DiagnosisRow> queryDiagnoses(Map<String, Object> params) {
        try {
            QueryResultDto result = aqlQueryService.query(AqlQueryRequest.prepare(DIAGNOSES_QUERY, params, null, null));
            if (result.getResultSet() == null) {
                return Collections.emptyList();
            }
            return result.getResultSet().stream()
                    .map(row -> {
                        List<Object> values = row.values();
                        return new DiagnosisRow(
                                asString(values, 1),
                                asString(values, 2),
                                asString(values, 3),
                                asString(values, 4),
                                asString(values, 5));
                    })
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to query diagnoses: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ProcedureRow> queryProcedures(Map<String, Object> params) {
        try {
            QueryResultDto result =
                    aqlQueryService.query(AqlQueryRequest.prepare(PROCEDURES_QUERY, params, null, null));
            if (result.getResultSet() == null) {
                return Collections.emptyList();
            }
            return result.getResultSet().stream()
                    .map(row -> {
                        List<Object> values = row.values();
                        return new ProcedureRow(
                                asString(values, 1), asString(values, 2), asString(values, 3), asString(values, 4));
                    })
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to query procedures: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<EncounterRow> queryEncounters(Map<String, Object> params) {
        try {
            QueryResultDto result =
                    aqlQueryService.query(AqlQueryRequest.prepare(ENCOUNTERS_QUERY, params, null, null));
            if (result.getResultSet() == null) {
                return Collections.emptyList();
            }
            return result.getResultSet().stream()
                    .map(row -> {
                        List<Object> values = row.values();
                        return new EncounterRow(
                                asString(values, 1),
                                asString(values, 2),
                                asString(values, 3),
                                asString(values, 4),
                                asString(values, 5),
                                asString(values, 6));
                    })
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to query encounters: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void validateBillingCodes(List<DiagnosisRow> diagnoses, List<ProcedureRow> procedures) {
        List<String> errors = new ArrayList<>();

        for (DiagnosisRow d : diagnoses) {
            if (d.code() != null && d.codingSystem() != null) {
                String systemUrl = resolveCodeSystem(d.codingSystem());
                ValidationResult vr = billingCodeValidator.validate(systemUrl, d.code());
                if (!vr.valid()) {
                    errors.add("Diagnosis code '%s' (%s): %s".formatted(d.code(), systemUrl, vr.message()));
                }
            }
        }

        for (ProcedureRow p : procedures) {
            if (p.code() != null && p.codeSystem() != null) {
                String systemUrl = resolveCodeSystem(p.codeSystem());
                ValidationResult vr = billingCodeValidator.validate(systemUrl, p.code());
                if (!vr.valid()) {
                    errors.add("Procedure code '%s' (%s): %s".formatted(p.code(), systemUrl, vr.message()));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Billing code validation failed: " + String.join("; ", errors));
        }
    }

    private List<ExplanationOfBenefit> buildEobResources(
            UUID ehrId, List<EncounterRow> encounters, List<DiagnosisRow> diagnoses, List<ProcedureRow> procedures) {

        if (encounters.isEmpty()) {
            ExplanationOfBenefit eob = createBaseEob(ehrId, null);
            addDiagnoses(eob, diagnoses);
            addProcedureItems(eob, procedures);
            return List.of(eob);
        }

        List<ExplanationOfBenefit> result = new ArrayList<>();
        for (EncounterRow encounter : encounters) {
            ExplanationOfBenefit eob = createBaseEob(ehrId, encounter);
            addDiagnoses(eob, diagnoses);
            addProcedureItems(eob, procedures);
            result.add(eob);
        }
        return result;
    }

    private ExplanationOfBenefit createBaseEob(UUID ehrId, EncounterRow encounter) {
        ExplanationOfBenefit eob = new ExplanationOfBenefit();

        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        eob.setUse(Use.CLAIM);
        eob.setPatient(new Reference("Patient/" + ehrId));

        eob.getType()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                        .setCode("professional")
                        .setDisplay("Professional"));

        if (encounter != null) {
            if (encounter.compositionUid() != null) {
                eob.setId(encounter.compositionUid());
            }
            if (encounter.facilityName() != null) {
                eob.getFacility().setDisplay(encounter.facilityName());
            }
            if (encounter.admissionDate() != null || encounter.dischargeDate() != null) {
                Period period = new Period();
                if (encounter.admissionDate() != null) {
                    period.setStart(parseDate(encounter.admissionDate()));
                }
                if (encounter.dischargeDate() != null) {
                    period.setEnd(parseDate(encounter.dischargeDate()));
                }
                eob.setBillablePeriod(period);
            }
            if (encounter.attendingProvider() != null) {
                eob.getProvider().setDisplay(encounter.attendingProvider());
            }
        }

        eob.setInsurer(new Reference().setDisplay("Unknown"));
        return eob;
    }

    private void addDiagnoses(ExplanationOfBenefit eob, List<DiagnosisRow> diagnoses) {
        int seq = 1;
        for (DiagnosisRow d : diagnoses) {
            DiagnosisComponent dc = new DiagnosisComponent();
            dc.setSequence(seq++);

            CodeableConcept concept = new CodeableConcept();
            String systemUrl = resolveCodeSystem(d.codingSystem());
            concept.addCoding(
                    new Coding().setSystem(systemUrl).setCode(d.code()).setDisplay(d.diagnosis()));
            concept.setText(d.diagnosis());
            dc.setDiagnosis(concept);

            eob.addDiagnosis(dc);
        }
    }

    private void addProcedureItems(ExplanationOfBenefit eob, List<ProcedureRow> procedures) {
        int seq = 1;
        for (ProcedureRow p : procedures) {
            ItemComponent item = new ItemComponent();
            item.setSequence(seq++);

            String systemUrl = resolveCodeSystem(p.codeSystem());
            CodeableConcept service = new CodeableConcept();
            service.addCoding(
                    new Coding().setSystem(systemUrl).setCode(p.code()).setDisplay(p.name()));
            service.setText(p.name());
            item.setProductOrService(service);

            if (p.dateOfService() != null) {
                Period servicePeriod = new Period();
                servicePeriod.setStart(parseDate(p.dateOfService()));
                item.setServiced(servicePeriod);
            }

            eob.addItem(item);
        }
    }

    static String resolveCodeSystem(String terminologyId) {
        if (terminologyId == null) {
            return "http://terminology.hl7.org/CodeSystem/data-absent-reason";
        }
        return switch (terminologyId.toUpperCase()) {
            case "ICD10", "ICD-10", "ICD10-CM" -> BillingCodeValidator.ICD10_SYSTEM;
            case "CPT", "CPT-4" -> BillingCodeValidator.CPT_SYSTEM;
            case "HCPCS" -> BillingCodeValidator.HCPCS_SYSTEM;
            case "SNOMED", "SNOMED-CT", "SNOMEDCT" -> BillingCodeValidator.SNOMED_SYSTEM;
            default -> terminologyId;
        };
    }

    private static String asString(List<Object> values, int index) {
        if (index >= values.size() || values.get(index) == null) {
            return null;
        }
        return values.get(index).toString();
    }

    private static Date parseDate(String dateString) {
        if (dateString == null) {
            return null;
        }
        try {
            return java.sql.Timestamp.valueOf(dateString.replace("T", " ").replaceAll("Z$", ""));
        } catch (Exception e) {
            try {
                return java.sql.Date.valueOf(dateString.substring(0, 10));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    record DiagnosisRow(
            String diagnosis, String code, String codingSystem, String dateOfOnset, String clinicalSetting) {}

    record ProcedureRow(String name, String code, String codeSystem, String dateOfService) {}

    record EncounterRow(
            String admissionDate,
            String dischargeDate,
            String encounterType,
            String facilityName,
            String attendingProvider,
            String compositionUid) {}
}
