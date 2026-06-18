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
package org.ehrbase.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Event;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BillingCodeValidatorTest {

    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";

    @Mock
    private FhirTerminologyValidation fhirTerminologyValidation;

    private BillingCodeValidator validator;
    private Map<String, BillingProfile> billingProfiles;

    @BeforeEach
    void setUp() {
        billingProfiles = new HashMap<>();

        BillingProfile usClaimsProfile = new BillingProfile();
        usClaimsProfile.setEnabled(true);
        usClaimsProfile.setRequiredCodeSystems(List.of(ICD10_SYSTEM, CPT_SYSTEM, SNOMED_SYSTEM));
        billingProfiles.put("us-claims", usClaimsProfile);

        validator = new BillingCodeValidator(fhirTerminologyValidation, billingProfiles);
    }

    @Test
    void validate_validIcd10Code_noViolations() {
        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "E11.9", "Type 2 diabetes mellitus");
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validate(composition, "us-claims");
    }

    @Test
    void validate_validCptCode_noViolations() {
        Composition composition = buildCompositionWithCodedEntry(CPT_SYSTEM, "99213", "Office visit");
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validate(composition, "us-claims");
    }

    @Test
    void validate_validSnomedCode_noViolations() {
        Composition composition = buildCompositionWithCodedEntry(SNOMED_SYSTEM, "73211009", "Diabetes mellitus");
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validate(composition, "us-claims");
    }

    @Test
    void validate_invalidIcd10Code_throwsConstraintViolation() {
        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "INVALID", "Invalid diagnosis");
        when(fhirTerminologyValidation.batchValidate(anyList()))
                .thenReturn(List.of(new ConstraintViolation(
                        "The specified code 'INVALID' is not known to belong to code system '" + ICD10_SYSTEM + "'")));

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .hasSize(1)
                .first()
                .extracting(Object::toString)
                .asString()
                .contains("INVALID")
                .contains(ICD10_SYSTEM);
    }

    @Test
    void validate_invalidCptCode_throwsConstraintViolation() {
        Composition composition = buildCompositionWithCodedEntry(CPT_SYSTEM, "00000", "Invalid procedure");
        when(fhirTerminologyValidation.batchValidate(anyList()))
                .thenReturn(List.of(new ConstraintViolation(
                        "The specified code '00000' is not known to belong to code system '" + CPT_SYSTEM + "'")));

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void validate_invalidSnomedCode_throwsConstraintViolation() {
        Composition composition = buildCompositionWithCodedEntry(SNOMED_SYSTEM, "999999999", "Invalid finding");
        when(fhirTerminologyValidation.batchValidate(anyList()))
                .thenReturn(List.of(
                        new ConstraintViolation("The specified code '999999999' is not known to belong to code system '"
                                + SNOMED_SYSTEM
                                + "'")));

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void validate_nonBillingTerminology_skipsValidation() {
        Composition composition = buildCompositionWithCodedEntry("local", "at0001", "Local term");
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validate(composition, "us-claims");
    }

    @Test
    void validate_unknownProfile_throwsIllegalArgument() {
        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "E11.9", "Test");

        assertThatThrownBy(() -> validator.validate(composition, "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void validate_disabledProfile_skipsValidation() {
        BillingProfile disabledProfile = new BillingProfile();
        disabledProfile.setEnabled(false);
        disabledProfile.setRequiredCodeSystems(List.of(ICD10_SYSTEM));
        billingProfiles.put("disabled-profile", disabledProfile);

        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "E11.9", "Test");

        validator.validate(composition, "disabled-profile");

        verify(fhirTerminologyValidation, never()).batchValidate(anyList());
    }

    @Test
    void validate_multipleCodesWithMixedResults_reportsAllViolations() {
        Composition composition = buildCompositionWithMultipleCodedEntries(
                List.of(ICD10_SYSTEM, CPT_SYSTEM),
                List.of("E11.9", "00000"),
                List.of("Diabetes mellitus", "Invalid procedure"));

        when(fhirTerminologyValidation.batchValidate(anyList()))
                .thenReturn(List.of(new ConstraintViolation(
                        "The specified code '00000' is not known to belong to code system '" + CPT_SYSTEM + "'")));

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .hasSize(1);
    }

    @Test
    void validate_emptyComposition_noViolations() {
        Composition composition = new Composition();
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validate(composition, "us-claims");
    }

    @Test
    void validateAll_validatesAllEnabledProfiles() {
        BillingProfile secondProfile = new BillingProfile();
        secondProfile.setEnabled(true);
        secondProfile.setRequiredCodeSystems(List.of(ICD10_SYSTEM));
        billingProfiles.put("eu-claims", secondProfile);

        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "E11.9", "Test");
        when(fhirTerminologyValidation.batchValidate(anyList())).thenReturn(Collections.emptyList());

        validator.validateAll(composition);
    }

    @Test
    void extractCodedEntries_compositionWithObservation_extractsAll() {
        Composition composition = buildCompositionWithCodedEntry(ICD10_SYSTEM, "E11.9", "Diabetes");

        List<DvCodedText> entries = validator.extractCodedEntries(composition);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getDefiningCode().getCodeString()).isEqualTo("E11.9");
    }

    private Composition buildCompositionWithCodedEntry(String system, String code, String display) {
        Composition composition = new Composition();

        CodePhrase codePhrase = new CodePhrase(new TerminologyId(system), code);
        DvCodedText dvCodedText = new DvCodedText(display, codePhrase);

        Element element = new Element("at0001", new DvText("test"), dvCodedText);
        ItemTree itemTree = new ItemTree("at0002", new DvText("Tree"), List.of(element));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setData(itemTree);

        History<ItemStructure> history = new History<>();
        List<Event<ItemStructure>> events = new ArrayList<>();
        events.add(event);
        history.setEvents(events);

        Observation observation = new Observation();
        observation.setData(history);

        composition.setContent(new ArrayList<>(List.of(observation)));
        return composition;
    }

    private Composition buildCompositionWithMultipleCodedEntries(
            List<String> systems, List<String> codes, List<String> displays) {
        Composition composition = new Composition();

        List<com.nedap.archie.rm.datastructures.Item> elements = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) {
            CodePhrase codePhrase = new CodePhrase(new TerminologyId(systems.get(i)), codes.get(i));
            DvCodedText dvCodedText = new DvCodedText(displays.get(i), codePhrase);
            Element element = new Element("at000" + (i + 1), new DvText("test" + i), dvCodedText);
            elements.add(element);
        }

        ItemTree itemTree = new ItemTree("at0002", new DvText("Tree"), elements);

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setData(itemTree);

        History<ItemStructure> history = new History<>();
        List<Event<ItemStructure>> events = new ArrayList<>();
        events.add(event);
        history.setEvents(events);

        Observation observation = new Observation();
        observation.setData(history);

        composition.setContent(new ArrayList<>(List.of(observation)));
        return composition;
    }
}
