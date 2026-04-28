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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Event;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import java.util.Map;
import org.ehrbase.configuration.config.validation.ExternalValidationProperties;
import org.ehrbase.configuration.config.validation.ExternalValidationProperties.BillingProfile;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class BillingCodeValidatorTest {

    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS_SYSTEM = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    private static final String PROFILE_NAME = "us-claims";

    private FhirTerminologyValidation fhirValidationMock;
    private ExternalValidationProperties properties;
    private BillingCodeValidator validator;

    @BeforeEach
    void setUp() {
        fhirValidationMock = mock(FhirTerminologyValidation.class);
        properties = new ExternalValidationProperties();
        validator = new BillingCodeValidator(fhirValidationMock, properties);
    }

    private void enableProfile(String profileName, List<String> codeSystems) {
        properties.setEnabled(true);
        BillingProfile profile = new BillingProfile();
        profile.setName(profileName);
        profile.setEnabled(true);
        profile.setCodeSystems(codeSystems);
        properties.getBillingProfiles().put(profileName, profile);
    }

    private Composition compositionWithCodes(CodePhrase... codePhrases) {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.test.v1");
        composition.setName(new DvText("Test"));

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.test.v1");
        observation.setName(new DvText("Obs"));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0001");
        itemTree.setName(new DvText("Tree"));

        for (CodePhrase cp : codePhrases) {
            Element element = new Element();
            element.setArchetypeNodeId("at0002");
            element.setName(new DvText("Element"));
            element.setValue(new DvCodedText("code-value", cp));
            itemTree.addItem(element);
        }

        Event<ItemTree> event = new Event<>() {
            {
                setArchetypeNodeId("at0003");
                setName(new DvText("Event"));
                setData(itemTree);
            }
        };

        History<ItemTree> history = new History<>();
        history.setArchetypeNodeId("at0004");
        history.setName(new DvText("History"));
        history.setEvents(List.of(event));

        observation.setData(history);
        composition.setContent(List.of(observation));

        return composition;
    }

    private static CodePhrase codePhrase(String system, String code) {
        return new CodePhrase(new TerminologyId(system), code);
    }

    @Test
    void icd10ValidCode_returnsNoViolations() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM, HCPCS_SYSTEM));

        CodePhrase cp = codePhrase(ICD10_SYSTEM, "E11.9");
        Composition composition = compositionWithCodes(cp);

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                results.put(tp, Try.success(Boolean.TRUE));
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertTrue(violations.isEmpty());
    }

    @Test
    void icd10InvalidCode_returnsViolation() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM, HCPCS_SYSTEM));

        CodePhrase cp = codePhrase(ICD10_SYSTEM, "INVALID");
        Composition composition = compositionWithCodes(cp);

        ConstraintViolation violation = new ConstraintViolation(
                "The specified code 'INVALID' is not known to belong to the specified code system '" + ICD10_SYSTEM
                        + "'");

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                results.put(tp, Try.failure(new ConstraintViolationException(List.of(violation))));
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("INVALID"));
        assertTrue(violations.get(0).getMessage().contains(ICD10_SYSTEM));
    }

    @Test
    void cptValidCode_returnsNoViolations() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM, HCPCS_SYSTEM));

        CodePhrase cp = codePhrase(CPT_SYSTEM, "99213");
        Composition composition = compositionWithCodes(cp);

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                results.put(tp, Try.success(Boolean.TRUE));
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertTrue(violations.isEmpty());
    }

    @Test
    void hcpcsValidCode_returnsNoViolations() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM, HCPCS_SYSTEM));

        CodePhrase cp = codePhrase(HCPCS_SYSTEM, "A0021");
        Composition composition = compositionWithCodes(cp);

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                results.put(tp, Try.success(Boolean.TRUE));
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertTrue(violations.isEmpty());
    }

    @Test
    void mixedCodes_someInvalid_returnsOnlyInvalidViolations() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM));

        CodePhrase validIcd = codePhrase(ICD10_SYSTEM, "E11.9");
        CodePhrase invalidCpt = codePhrase(CPT_SYSTEM, "XXXXX");
        Composition composition = compositionWithCodes(validIcd, invalidCpt);

        ConstraintViolation cptViolation = new ConstraintViolation(
                "The specified code 'XXXXX' is not known to belong to the specified code system '" + CPT_SYSTEM + "'");

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                if (tp.getCodePhrase()
                        .map(cp -> "XXXXX".equals(cp.getCodeString()))
                        .orElse(false)) {
                    results.put(tp, Try.failure(new ConstraintViolationException(List.of(cptViolation))));
                } else {
                    results.put(tp, Try.success(Boolean.TRUE));
                }
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("XXXXX"));
    }

    @Test
    void profileDisabled_returnsEmptyViolations() {
        properties.setEnabled(true);
        BillingProfile profile = new BillingProfile();
        profile.setName(PROFILE_NAME);
        profile.setEnabled(false);
        profile.setCodeSystems(List.of(ICD10_SYSTEM));
        properties.getBillingProfiles().put(PROFILE_NAME, profile);

        CodePhrase cp = codePhrase(ICD10_SYSTEM, "E11.9");
        Composition composition = compositionWithCodes(cp);

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertTrue(violations.isEmpty());
        verify(fhirValidationMock, never()).validate(any());
    }

    @Test
    void profileNotFound_returnsEmptyViolations() {
        properties.setEnabled(true);
        CodePhrase cp = codePhrase(ICD10_SYSTEM, "E11.9");
        Composition composition = compositionWithCodes(cp);

        List<ConstraintViolation> violations = validator.validateComposition(composition, "nonexistent-profile");

        assertTrue(violations.isEmpty());
        verify(fhirValidationMock, never()).validate(any());
    }

    @Test
    void externalTerminologyDisabled_returnsEmptyViolations() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM));
        properties.setEnabled(false);

        CodePhrase cp = codePhrase(ICD10_SYSTEM, "E11.9");
        Composition composition = compositionWithCodes(cp);

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertTrue(violations.isEmpty());
        verify(fhirValidationMock, never()).validate(any());
        verify(fhirValidationMock, never()).validateBatch(any());
    }

    @Test
    void batchValidation_returnsCorrectPerCodeResults() {
        enableProfile(PROFILE_NAME, List.of(ICD10_SYSTEM, CPT_SYSTEM));

        CodePhrase validIcd = codePhrase(ICD10_SYSTEM, "E11.9");
        CodePhrase validCpt = codePhrase(CPT_SYSTEM, "99213");
        CodePhrase invalidIcd = codePhrase(ICD10_SYSTEM, "ZZZ");
        Composition composition = compositionWithCodes(validIcd, validCpt, invalidIcd);

        ConstraintViolation icdViolation = new ConstraintViolation(
                "The specified code 'ZZZ' is not known to belong to the specified code system '" + ICD10_SYSTEM + "'");

        when(fhirValidationMock.validateBatch(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<TerminologyParam> params = invocation.getArgument(0);
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> results = new java.util.HashMap<>();
            for (TerminologyParam tp : params) {
                if (tp.getCodePhrase()
                        .map(cp -> "ZZZ".equals(cp.getCodeString()))
                        .orElse(false)) {
                    results.put(tp, Try.failure(new ConstraintViolationException(List.of(icdViolation))));
                } else {
                    results.put(tp, Try.success(Boolean.TRUE));
                }
            }
            return results;
        });

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("ZZZ"));
    }
}
