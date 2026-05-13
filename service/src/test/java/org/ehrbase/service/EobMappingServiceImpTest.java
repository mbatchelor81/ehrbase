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
package org.ehrbase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.EobMappingService.EobResult;
import org.ehrbase.repository.CompositionRepository;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EobMappingServiceImpTest {

    @Mock
    private CompositionService compositionService;

    @Mock
    private CompositionRepository compositionRepository;

    @Mock
    private EhrService ehrService;

    private EobMappingServiceImp mappingService;

    @BeforeEach
    void setUp() {
        mappingService = new EobMappingServiceImp(compositionService, compositionRepository, ehrService);
    }

    @Test
    void mapToEob_setsPatientReference() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getPatient().getReference()).isEqualTo("Patient/" + ehrId);
    }

    @Test
    void mapToEob_setsStatusAndUse() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getStatus()).isEqualTo(ExplanationOfBenefit.ExplanationOfBenefitStatus.ACTIVE);
        assertThat(eob.getUse()).isEqualTo(ExplanationOfBenefit.Use.CLAIM);
    }

    @Test
    void mapToEob_setsIdFromCompositionUid() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();
        String uid = UUID.randomUUID() + "::local.ehrbase.org::1";
        composition.setUid(new ObjectVersionId(uid));

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getId()).isEqualTo(uid);
    }

    @Test
    void mapToEob_setsProviderFromComposer() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();
        PartyIdentified composer = new PartyIdentified();
        composer.setName("Dr. Smith");
        composition.setComposer(composer);

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getProvider().getDisplay()).isNotEmpty();
    }

    @Test
    void mapToEob_setsTypeAsProfessional() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getType().getCodingFirstRep().getCode()).isEqualTo("professional");
        assertThat(eob.getType().getCodingFirstRep().getSystem())
                .isEqualTo("http://terminology.hl7.org/CodeSystem/claim-type");
    }

    @Test
    void mapToEob_extractsDiagnosisFromEvaluation() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        Evaluation evaluation = new Evaluation();
        DvCodedText codedName = new DvCodedText("Diabetes", new com.nedap.archie.rm.datatypes.CodePhrase());
        codedName.getDefiningCode().setCodeString("E11");
        codedName.getDefiningCode().setTerminologyId(new TerminologyId("ICD-10"));
        evaluation.setName(codedName);
        evaluation.setArchetypeNodeId("openEHR-EHR-EVALUATION.diagnosis.v1");
        evaluation.setLanguage(new com.nedap.archie.rm.datatypes.CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        evaluation.setEncoding(
                new com.nedap.archie.rm.datatypes.CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        evaluation.setSubject(new PartyIdentified());

        composition.setContent(List.of(evaluation));

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getDiagnosis().get(0).getSequence()).isEqualTo(1);
        assertThat(eob.getDiagnosis()
                        .get(0)
                        .getDiagnosisCodeableConcept()
                        .getCodingFirstRep()
                        .getCode())
                .isEqualTo("E11");
    }

    @Test
    void mapToEob_extractsDiagnosisFromSection() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        Section section = new Section();
        section.setName(new DvText("Diagnoses"));
        section.setArchetypeNodeId("openEHR-EHR-SECTION.diagnoses.v1");

        Evaluation evaluation = new Evaluation();
        evaluation.setName(new DvText("Hypertension"));
        evaluation.setArchetypeNodeId("openEHR-EHR-EVALUATION.diagnosis.v1");
        evaluation.setLanguage(new com.nedap.archie.rm.datatypes.CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        evaluation.setEncoding(
                new com.nedap.archie.rm.datatypes.CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        evaluation.setSubject(new PartyIdentified());

        section.setItems(List.of(evaluation));
        composition.setContent(List.of(section));

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getDiagnosis()).hasSize(1);
        assertThat(eob.getDiagnosis().get(0).getDiagnosisCodeableConcept().getText())
                .isEqualTo("Hypertension");
    }

    @Test
    void mapToEob_includesInsurance() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getInsurance()).hasSize(1);
        assertThat(eob.getInsurance().get(0).getFocal()).isTrue();
        assertThat(eob.getInsurance().get(0).getCoverage().getReference()).isEqualTo("Coverage/" + ehrId);
    }

    @Test
    void mapToEob_includesTotalAmount() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        ExplanationOfBenefit eob = mappingService.mapToEob(ehrId, composition);

        assertThat(eob.getTotal()).hasSize(1);
        assertThat(eob.getTotal().get(0).getAmount().getCurrency()).isEqualTo("USD");
    }

    @Test
    void getExplanationOfBenefits_routesThroughCompositionService() {
        UUID ehrId = UUID.randomUUID();
        UUID compId = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        doNothing().when(ehrService).checkEhrExists(ehrId);
        when(compositionRepository.findCompositionIdsByEhr(ehrId)).thenReturn(List.of(compId));
        when(compositionService.retrieve(eq(ehrId), eq(compId), isNull())).thenReturn(Optional.of(composition));

        EobResult result = mappingService.getExplanationOfBenefits(ehrId, 0, 10);

        assertThat(result.eobList()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
        verify(compositionService).retrieve(eq(ehrId), eq(compId), isNull());
        verify(ehrService).checkEhrExists(ehrId);
    }

    @Test
    void getExplanationOfBenefits_appliesPagination() {
        UUID ehrId = UUID.randomUUID();
        UUID compId1 = UUID.randomUUID();
        UUID compId2 = UUID.randomUUID();
        UUID compId3 = UUID.randomUUID();
        Composition composition = createMinimalComposition();

        doNothing().when(ehrService).checkEhrExists(ehrId);
        when(compositionRepository.findCompositionIdsByEhr(ehrId)).thenReturn(List.of(compId1, compId2, compId3));
        when(compositionService.retrieve(eq(ehrId), any(), isNull())).thenReturn(Optional.of(composition));

        EobResult result = mappingService.getExplanationOfBenefits(ehrId, 1, 1);

        assertThat(result.eobList()).hasSize(1);
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    void getExplanationOfBenefits_emptyWhenNoCompositions() {
        UUID ehrId = UUID.randomUUID();

        doNothing().when(ehrService).checkEhrExists(ehrId);
        when(compositionRepository.findCompositionIdsByEhr(ehrId)).thenReturn(List.of());

        EobResult result = mappingService.getExplanationOfBenefits(ehrId, 0, 10);

        assertThat(result.eobList()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
    }

    private Composition createMinimalComposition() {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Encounter"));
        return composition;
    }
}
