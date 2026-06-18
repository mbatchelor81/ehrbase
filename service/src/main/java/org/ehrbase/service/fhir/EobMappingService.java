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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ehrbase.api.dto.BillingCodeSystemConfig;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.DiagnosisComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ProcedureComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * Maps openEHR composition data (retrieved via AQL) to FHIR R4 ExplanationOfBenefit resources.
 * Uses billing profile code system configuration to classify codings.
 */
@Component
public class EobMappingService {

    private final BillingCodeSystemConfig codeSystemConfig;

    public EobMappingService(BillingCodeSystemConfig codeSystemConfig) {
        this.codeSystemConfig = codeSystemConfig;
    }

    /**
     * Maps a single AQL result row to an ExplanationOfBenefit resource.
     *
     * @param compositionId   the composition UUID
     * @param ehrId           the EHR UUID (patient reference)
     * @param templateId      the template identifier
     * @param startTime       composition start time
     * @param codings         list of coding maps with keys: system, code, display
     * @return a populated ExplanationOfBenefit
     */
    public ExplanationOfBenefit mapToEob(
            UUID compositionId, UUID ehrId, String templateId, Date startTime, List<Map<String, String>> codings) {

        ExplanationOfBenefit eob = new ExplanationOfBenefit();
        eob.setId(compositionId.toString());
        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        eob.setType(new CodeableConcept()
                .addCoding(
                        new Coding("http://terminology.hl7.org/CodeSystem/claim-type", "professional", "Professional"))
                .setText("Professional"));
        eob.setUse(Use.CLAIM);
        eob.setPatient(new Reference("Patient/" + ehrId));
        eob.setCreated(startTime != null ? startTime : new Date());
        eob.setInsurer(new Reference("Organization/unknown"));
        eob.setProvider(new Reference("Organization/unknown"));

        if (templateId != null) {
            eob.getMeta().addProfile("http://ehrbase.org/fhir/StructureDefinition/eob-from-" + templateId);
        }

        if (codings != null) {
            classifyCodings(eob, codings);
        }

        return eob;
    }

    private void classifyCodings(ExplanationOfBenefit eob, List<Map<String, String>> codings) {
        List<String> diagnosisSystems = codeSystemConfig.diagnosisCodeSystems();
        List<String> procedureSystems = codeSystemConfig.procedureCodeSystems();

        int diagSeq = 1;
        int procSeq = 1;
        int itemSeq = 1;

        for (Map<String, String> coding : codings) {
            String system = coding.get("system");
            String code = coding.get("code");
            String display = coding.get("display");

            if (system == null || code == null) {
                continue;
            }

            Coding fhirCoding = new Coding(system, code, display);

            if (matchesAnySystem(system, diagnosisSystems)) {
                DiagnosisComponent diag = new DiagnosisComponent();
                diag.setSequence(diagSeq++);
                diag.setDiagnosis(new CodeableConcept().addCoding(fhirCoding));
                eob.addDiagnosis(diag);
            } else if (matchesAnySystem(system, procedureSystems)) {
                ProcedureComponent proc = new ProcedureComponent();
                proc.setSequence(procSeq++);
                proc.setProcedure(new CodeableConcept().addCoding(fhirCoding));
                eob.addProcedure(proc);
            }

            ItemComponent item = new ItemComponent();
            item.setSequence(itemSeq++);
            item.setProductOrService(new CodeableConcept().addCoding(fhirCoding));
            eob.addItem(item);
        }
    }

    private static boolean matchesAnySystem(String system, List<String> knownSystems) {
        return knownSystems.stream().anyMatch(system::contains);
    }
}
