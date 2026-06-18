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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Cluster;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.junit.jupiter.api.Test;

class BillingCodeValidatorTest {

    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS_SYSTEM = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";

    private final ExternalTerminologyValidation terminologyValidation = mock(ExternalTerminologyValidation.class);

    private BillingCodeValidator createValidator() {
        return new BillingCodeValidator(
                Set.of(ICD10_SYSTEM), Set.of(CPT_SYSTEM, HCPCS_SYSTEM), Set.of(), terminologyValidation);
    }

    private BillingCodeValidator createValidatorWithSupporting() {
        return new BillingCodeValidator(
                Set.of(ICD10_SYSTEM), Set.of(CPT_SYSTEM), Set.of(SNOMED_SYSTEM), terminologyValidation);
    }

    // --- No-op / disabled profile tests ---

    @Test
    void validate_emptyComposition_returnsNoViolations() {
        BillingCodeValidator validator = createValidator();
        Composition composition = new Composition();
        composition.setContent(null);

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, never()).supports(any());
        verify(terminologyValidation, never()).validate(any());
    }

    @Test
    void validate_compositionWithEmptyContent_returnsNoViolations() {
        BillingCodeValidator validator = createValidator();
        Composition composition = new Composition();
        composition.setContent(Collections.emptyList());

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_noBillingCodeSystems_returnsNoViolations() {
        BillingCodeValidator validator = new BillingCodeValidator(Set.of(), Set.of(), Set.of(), terminologyValidation);

        Composition composition = compositionWithCodedElement(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, never()).supports(any());
    }

    @Test
    void validate_nonBillingCodes_areIgnored() {
        BillingCodeValidator validator = createValidator();

        Composition composition = compositionWithCodedElement(SNOMED_SYSTEM, "73211009");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, never()).supports(any());
    }

    // --- Valid ICD-10 code tests ---

    @Test
    void validate_validIcd10Code_returnsNoViolations() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithCodedElement(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation).supports(any());
        verify(terminologyValidation).validate(any());
    }

    // --- Invalid ICD-10 code tests ---

    @Test
    void validate_invalidIcd10Code_returnsViolation() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any()))
                .thenReturn(Try.failure(new ConstraintViolationException(
                        List.of(new ConstraintViolation("Code not found in ICD-10-CM")))));

        Composition composition = compositionWithCodedElement(ICD10_SYSTEM, "INVALID");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage())
                .contains("diagnosis")
                .contains(ICD10_SYSTEM)
                .contains("INVALID")
                .contains("Code not found in ICD-10-CM");
    }

    // --- Valid CPT/HCPCS code tests ---

    @Test
    void validate_validCptCode_returnsNoViolations() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithCodedElement(CPT_SYSTEM, "99213");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_invalidCptCode_returnsViolation() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any()))
                .thenReturn(Try.failure(
                        new ConstraintViolationException(List.of(new ConstraintViolation("Code not found in CPT")))));

        Composition composition = compositionWithCodedElement(CPT_SYSTEM, "XXXXX");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage())
                .contains("procedure")
                .contains(CPT_SYSTEM)
                .contains("XXXXX");
    }

    @Test
    void validate_invalidHcpcsCode_returnsViolation() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any()))
                .thenReturn(Try.failure(
                        new ConstraintViolationException(List.of(new ConstraintViolation("Code not found")))));

        Composition composition = compositionWithCodedElement(HCPCS_SYSTEM, "BADCODE");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage())
                .contains("procedure")
                .contains(HCPCS_SYSTEM)
                .contains("BADCODE");
    }

    // --- Supporting code system ---

    @Test
    void validate_supportingCodeSystem_classifiedCorrectly() {
        BillingCodeValidator validator = createValidatorWithSupporting();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any()))
                .thenReturn(Try.failure(
                        new ConstraintViolationException(List.of(new ConstraintViolation("Invalid code")))));

        Composition composition = compositionWithCodedElement(SNOMED_SYSTEM, "12345");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage()).contains("supporting");
    }

    // --- Duplicate de-duplication tests ---

    @Test
    void validate_duplicateCodes_validatedOnlyOnce() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithDuplicateCodes(ICD10_SYSTEM, "E11.9", 3);

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, times(1)).validate(any());
    }

    @Test
    void validate_differentCodes_eachValidated() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithMultipleDistinctCodes(ICD10_SYSTEM, List.of("E11.9", "J06.9", "I10"));

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, times(3)).validate(any());
    }

    @Test
    void validate_duplicateInvalidCodes_singleViolation() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any()))
                .thenReturn(
                        Try.failure(new ConstraintViolationException(List.of(new ConstraintViolation("Not found")))));

        Composition composition = compositionWithDuplicateCodes(ICD10_SYSTEM, "BAD", 5);

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(1);
        verify(terminologyValidation, times(1)).validate(any());
    }

    // --- Terminology server not supporting code system ---

    @Test
    void validate_terminologyServerDoesNotSupport_noViolations() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(false);

        Composition composition = compositionWithCodedElement(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, never()).validate(any());
    }

    // --- Mixed billing and non-billing codes ---

    @Test
    void validate_mixedBillingAndNonBilling_onlyBillingValidated() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithMixedCodes();

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        // Only the ICD-10 code should be validated, not the openehr local code
        verify(terminologyValidation, times(1)).validate(any());
    }

    // --- Nested structure traversal ---

    @Test
    void validate_nestedSection_traversesDeeply() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithNestedSection(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, times(1)).validate(any());
    }

    @Test
    void validate_observationWithHistory_traversesEvents() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithObservationHistory(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, times(1)).validate(any());
    }

    // --- Multiple violations from multiple code systems ---

    @Test
    void validate_multipleInvalidBillingCodes_returnsAllViolations() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any(TerminologyParam.class)))
                .thenReturn(
                        Try.failure(new ConstraintViolationException(List.of(new ConstraintViolation("Not found")))));

        Composition composition = compositionWithMultipleDistinctCodes(ICD10_SYSTEM, List.of("BAD1", "BAD2"));

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).hasSize(2);
    }

    // --- Cluster traversal ---

    @Test
    void validate_codedTextInCluster_isTraversed() {
        BillingCodeValidator validator = createValidator();
        when(terminologyValidation.supports(any())).thenReturn(true);
        when(terminologyValidation.validate(any())).thenReturn(Try.success(Boolean.TRUE));

        Composition composition = compositionWithCluster(ICD10_SYSTEM, "E11.9");

        List<ConstraintViolation> violations = validator.validate(composition);

        assertThat(violations).isEmpty();
        verify(terminologyValidation, times(1)).validate(any());
    }

    // --- Helper methods ---

    private static Composition compositionWithCodedElement(String system, String code) {
        Composition composition = new Composition();
        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("at0001");
        eval.setName(new DvText("eval"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        tree.setItems(List.<Item>of(codedElement(system, code)));
        eval.setData(tree);

        composition.setContent(List.of(eval));
        return composition;
    }

    private static Composition compositionWithDuplicateCodes(String system, String code, int count) {
        Composition composition = new Composition();
        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("at0001");
        eval.setName(new DvText("eval"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        List<Item> elements = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            elements.add(codedElement(system, code));
        }
        tree.setItems(elements);
        eval.setData(tree);

        composition.setContent(List.of(eval));
        return composition;
    }

    private static Composition compositionWithMultipleDistinctCodes(String system, List<String> codes) {
        Composition composition = new Composition();
        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("at0001");
        eval.setName(new DvText("eval"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        List<Item> elements = new java.util.ArrayList<>();
        for (String code : codes) {
            elements.add(codedElement(system, code));
        }
        tree.setItems(elements);
        eval.setData(tree);

        composition.setContent(List.of(eval));
        return composition;
    }

    private static Composition compositionWithMixedCodes() {
        Composition composition = new Composition();
        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("at0001");
        eval.setName(new DvText("eval"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        tree.setItems(List.<Item>of(codedElement(ICD10_SYSTEM, "E11.9"), codedElement("local", "at0005")));
        eval.setData(tree);

        composition.setContent(List.of(eval));
        return composition;
    }

    private static Composition compositionWithNestedSection(String system, String code) {
        Composition composition = new Composition();

        Section section = new Section();
        section.setArchetypeNodeId("at0010");
        section.setName(new DvText("section"));

        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("at0001");
        eval.setName(new DvText("eval"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        tree.setItems(List.<Item>of(codedElement(system, code)));
        eval.setData(tree);

        section.setItems(List.of(eval));
        composition.setContent(List.of(section));
        return composition;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Composition compositionWithObservationHistory(String system, String code) {
        Composition composition = new Composition();

        Observation obs = new Observation();
        obs.setArchetypeNodeId("at0001");
        obs.setName(new DvText("obs"));

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0003");
        history.setName(new DvText("history"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0004");
        event.setName(new DvText("event"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));
        tree.setItems(List.<Item>of(codedElement(system, code)));
        event.setData(tree);

        history.setEvents((List) List.of(event));
        obs.setData(history);

        composition.setContent(List.of(obs));
        return composition;
    }

    private static Composition compositionWithCluster(String system, String code) {
        Composition composition = new Composition();
        AdminEntry admin = new AdminEntry();
        admin.setArchetypeNodeId("at0001");
        admin.setName(new DvText("admin"));

        ItemTree tree = new ItemTree();
        tree.setArchetypeNodeId("at0002");
        tree.setName(new DvText("tree"));

        Cluster cluster = new Cluster();
        cluster.setArchetypeNodeId("at0010");
        cluster.setName(new DvText("cluster"));
        cluster.setItems(List.<Item>of(codedElement(system, code)));

        tree.setItems(List.of(cluster));
        admin.setData(tree);

        composition.setContent(List.of(admin));
        return composition;
    }

    private static Element codedElement(String system, String code) {
        Element element = new Element();
        element.setArchetypeNodeId("at0099");
        element.setName(new DvText("coded_element"));
        element.setValue(new DvCodedText("display", new CodePhrase(new TerminologyId(system), code)));
        return element;
    }
}
