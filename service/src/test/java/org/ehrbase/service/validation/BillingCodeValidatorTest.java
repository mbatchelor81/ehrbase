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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.internal.JsonContext;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import java.util.Map;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BillingCodeValidatorTest {

    private FhirTerminologyValidation fhirValidation;
    private BillingCodeValidator validator;

    private static final BillingCodeValidator.BillingProfileConfig US_CLAIMS_PROFILE =
            new BillingCodeValidator.BillingProfileConfig(
                    "us-claims",
                    "US Claims billing profile",
                    List.of(
                            BillingCodeValidator.ICD10_SYSTEM,
                            BillingCodeValidator.CPT_SYSTEM,
                            BillingCodeValidator.SNOMED_CT_SYSTEM),
                    true,
                    null);

    private static final BillingCodeValidator.BillingProfileConfig DISABLED_PROFILE =
            new BillingCodeValidator.BillingProfileConfig("disabled", "Disabled profile", List.of(), false, null);

    @BeforeEach
    void setUp() {
        fhirValidation = spy(new FhirTerminologyValidation("http://terminology.local"));
        validator = new BillingCodeValidator(
                fhirValidation,
                Map.of(
                        "us-claims", US_CLAIMS_PROFILE,
                        "disabled", DISABLED_PROFILE));
    }

    // -- ICD-10 validation tests --

    @Test
    void validateComposition_validIcd10Code_succeeds() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9");
        stubValidateCodeSuccess(BillingCodeValidator.ICD10_SYSTEM, icd10Code);

        Composition composition = compositionWith(icd10Code);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateComposition_invalidIcd10Code_returnsViolation() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "INVALID");
        stubValidateCodeFailure(BillingCodeValidator.ICD10_SYSTEM, icd10Code, "Code 'INVALID' not found in ICD-10-CM");

        Composition composition = compositionWith(icd10Code);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isFailure());
        ConstraintViolationException ex = result.getAsFailure().get();
        assertEquals(1, ex.getConstraintViolations().size());
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains("INVALID"));
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains(BillingCodeValidator.ICD10_SYSTEM));
    }

    // -- CPT validation tests --

    @Test
    void validateComposition_validCptCode_succeeds() {
        CodePhrase cptCode = new CodePhrase(new TerminologyId(BillingCodeValidator.CPT_SYSTEM), "99213");
        stubValidateCodeSuccess(BillingCodeValidator.CPT_SYSTEM, cptCode);

        Composition composition = compositionWith(cptCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateComposition_invalidCptCode_returnsViolation() {
        CodePhrase cptCode = new CodePhrase(new TerminologyId(BillingCodeValidator.CPT_SYSTEM), "00000");
        stubValidateCodeFailure(BillingCodeValidator.CPT_SYSTEM, cptCode, "Code '00000' not found in CPT");

        Composition composition = compositionWith(cptCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isFailure());
        ConstraintViolationException ex = result.getAsFailure().get();
        assertEquals(1, ex.getConstraintViolations().size());
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains("00000"));
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains(BillingCodeValidator.CPT_SYSTEM));
    }

    // -- SNOMED CT validation tests --

    @Test
    void validateComposition_validSnomedCode_succeeds() {
        CodePhrase snomedCode = new CodePhrase(new TerminologyId(BillingCodeValidator.SNOMED_CT_SYSTEM), "73211009");
        stubValidateCodeSuccess(BillingCodeValidator.SNOMED_CT_SYSTEM, snomedCode);

        Composition composition = compositionWith(snomedCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateComposition_invalidSnomedCode_returnsViolation() {
        CodePhrase snomedCode = new CodePhrase(new TerminologyId(BillingCodeValidator.SNOMED_CT_SYSTEM), "9999999");
        stubValidateCodeFailure(
                BillingCodeValidator.SNOMED_CT_SYSTEM, snomedCode, "Code '9999999' not found in SNOMED CT");

        Composition composition = compositionWith(snomedCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isFailure());
        ConstraintViolationException ex = result.getAsFailure().get();
        assertEquals(1, ex.getConstraintViolations().size());
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains("9999999"));
    }

    // -- Profile behavior tests --

    @Test
    void validateComposition_unknownProfile_returnsFailure() {
        Composition composition =
                compositionWith(new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9"));

        Try<Boolean, ConstraintViolationException> result =
                validator.validateComposition(composition, "nonexistent-profile");

        assertTrue(result.isFailure());
        assertTrue(result.getAsFailure()
                .get()
                .getConstraintViolations()
                .get(0)
                .getMessage()
                .contains("Unknown billing profile"));
    }

    @Test
    void validateComposition_disabledProfile_skipsValidation() {
        Composition composition =
                compositionWith(new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9"));

        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "disabled");

        assertTrue(result.isSuccess());
        verify(fhirValidation, never()).batchValidateCodes(anyMap());
    }

    @Test
    void validateComposition_codeSystemNotInProfile_skipsValidation() {
        CodePhrase hcpcsCode = new CodePhrase(new TerminologyId(BillingCodeValidator.HCPCS_SYSTEM), "A0021");

        Composition composition = compositionWith(hcpcsCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateComposition_multipleInvalidCodes_returnsAllViolations() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "INVALID1");
        CodePhrase cptCode = new CodePhrase(new TerminologyId(BillingCodeValidator.CPT_SYSTEM), "INVALID2");

        stubValidateCodeFailure(BillingCodeValidator.ICD10_SYSTEM, icd10Code, "Code 'INVALID1' not found");
        stubValidateCodeFailure(BillingCodeValidator.CPT_SYSTEM, cptCode, "Code 'INVALID2' not found");

        Composition composition = compositionWithMultiple(icd10Code, cptCode);
        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isFailure());
        ConstraintViolationException ex = result.getAsFailure().get();
        assertEquals(2, ex.getConstraintViolations().size());
    }

    @Test
    void validateComposition_emptyComposition_succeeds() {
        Composition composition = new Composition();

        Try<Boolean, ConstraintViolationException> result = validator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    // -- validateCodedEntries tests --

    @Test
    void validateCodedEntries_validCodes_succeeds() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9");
        stubValidateCodeSuccess(BillingCodeValidator.ICD10_SYSTEM, icd10Code);

        Try<Boolean, ConstraintViolationException> result =
                validator.validateCodedEntries(List.of(icd10Code), "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateCodedEntries_invalidCode_returnsViolation() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "BAD");
        stubValidateCodeFailure(BillingCodeValidator.ICD10_SYSTEM, icd10Code, "Code 'BAD' not found");

        Try<Boolean, ConstraintViolationException> result =
                validator.validateCodedEntries(List.of(icd10Code), "us-claims");

        assertTrue(result.isFailure());
    }

    // -- extractCodedEntries tests --

    @Test
    void extractCodedEntries_nullComposition_returnsEmpty() {
        List<CodePhrase> entries = BillingCodeValidator.extractCodedEntries(null);
        assertTrue(entries.isEmpty());
    }

    @Test
    void extractCodedEntries_compositionWithCodedElement_extractsCode() {
        CodePhrase code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9");
        Composition composition = compositionWith(code);

        List<CodePhrase> entries = BillingCodeValidator.extractCodedEntries(composition);

        assertFalse(entries.isEmpty());
        assertEquals("E11.9", entries.get(0).getCodeString());
        assertEquals(
                BillingCodeValidator.ICD10_SYSTEM,
                entries.get(0).getTerminologyId().getValue());
    }

    // -- Batch validation test on FhirTerminologyValidation --

    @Test
    void batchValidateCodes_multipleCodeSystems_returnsPerSystemResults() {
        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9");
        CodePhrase snomedCode = new CodePhrase(new TerminologyId(BillingCodeValidator.SNOMED_CT_SYSTEM), "73211009");

        JsonContext jsonContextSuccess = Mockito.mock(JsonContext.class);
        Mockito.when(jsonContextSuccess.read("$.parameter[0].valueBoolean", boolean.class))
                .thenReturn(true);

        doReturn(jsonContextSuccess).when(fhirValidation).internalGet(Mockito.anyString());

        Map<String, Try<Boolean, ConstraintViolationException>> results = fhirValidation.batchValidateCodes(Map.of(
                BillingCodeValidator.ICD10_SYSTEM, icd10Code, BillingCodeValidator.SNOMED_CT_SYSTEM, snomedCode));

        assertEquals(2, results.size());
        assertTrue(results.containsKey(BillingCodeValidator.ICD10_SYSTEM));
        assertTrue(results.containsKey(BillingCodeValidator.SNOMED_CT_SYSTEM));
    }

    // -- Integration test with mock FHIR server --

    @Test
    void validateComposition_integrationWithMockServer_validIcd10() {
        String baseUrl = "http://mock-fhir.local";
        FhirTerminologyValidation mockFhir = spy(new FhirTerminologyValidation(baseUrl));

        String validateResponse =
                "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true}]}";
        JsonContext successContext = (JsonContext) JsonPath.parse(validateResponse);

        doReturn(successContext).when(mockFhir).internalGet(Mockito.anyString());

        BillingCodeValidator integrationValidator =
                new BillingCodeValidator(mockFhir, Map.of("us-claims", US_CLAIMS_PROFILE));

        CodePhrase icd10Code = new CodePhrase(new TerminologyId(BillingCodeValidator.ICD10_SYSTEM), "E11.9");
        Composition composition = compositionWith(icd10Code);

        Try<Boolean, ConstraintViolationException> result =
                integrationValidator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    @Test
    void validateComposition_integrationWithMockServer_invalidCpt() {
        String baseUrl = "http://mock-fhir.local";
        FhirTerminologyValidation mockFhir = spy(new FhirTerminologyValidation(baseUrl));

        String validateResponse =
                "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":false},{\"name\":\"message\",\"valueString\":\"Code 99999 not found in CPT\"}]}";
        JsonContext failContext = (JsonContext) JsonPath.parse(validateResponse);

        doReturn(failContext).when(mockFhir).internalGet(Mockito.anyString());

        BillingCodeValidator integrationValidator =
                new BillingCodeValidator(mockFhir, Map.of("us-claims", US_CLAIMS_PROFILE));

        CodePhrase cptCode = new CodePhrase(new TerminologyId(BillingCodeValidator.CPT_SYSTEM), "99999");
        Composition composition = compositionWith(cptCode);

        Try<Boolean, ConstraintViolationException> result =
                integrationValidator.validateComposition(composition, "us-claims");

        assertTrue(result.isFailure());
        ConstraintViolationException ex = result.getAsFailure().get();
        assertTrue(ex.getConstraintViolations().get(0).getMessage().contains("99999"));
    }

    @Test
    void validateComposition_integrationWithMockServer_validSnomed() {
        String baseUrl = "http://mock-fhir.local";
        FhirTerminologyValidation mockFhir = spy(new FhirTerminologyValidation(baseUrl));

        String validateResponse =
                "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true}]}";
        JsonContext successContext = (JsonContext) JsonPath.parse(validateResponse);

        doReturn(successContext).when(mockFhir).internalGet(Mockito.anyString());

        BillingCodeValidator integrationValidator =
                new BillingCodeValidator(mockFhir, Map.of("us-claims", US_CLAIMS_PROFILE));

        CodePhrase snomedCode = new CodePhrase(new TerminologyId(BillingCodeValidator.SNOMED_CT_SYSTEM), "73211009");
        Composition composition = compositionWith(snomedCode);

        Try<Boolean, ConstraintViolationException> result =
                integrationValidator.validateComposition(composition, "us-claims");

        assertTrue(result.isSuccess());
    }

    // -- Helper methods --

    private void stubValidateCodeSuccess(String system, CodePhrase codePhrase) {
        String validateResponse =
                "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true}]}";
        JsonContext jsonContext = (JsonContext) JsonPath.parse(validateResponse);

        doReturn(jsonContext)
                .when(fhirValidation)
                .internalGet(Mockito.contains("/CodeSystem/$validate-code?url=" + system));
    }

    private void stubValidateCodeFailure(String system, CodePhrase codePhrase, String message) {
        String validateResponse =
                "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":false},{\"name\":\"message\",\"valueString\":\""
                        + message + "\"}]}";
        JsonContext jsonContext = (JsonContext) JsonPath.parse(validateResponse);

        doReturn(jsonContext)
                .when(fhirValidation)
                .internalGet(Mockito.contains("/CodeSystem/$validate-code?url=" + system));
    }

    @SuppressWarnings("unchecked")
    private static Composition compositionWith(CodePhrase codePhrase) {
        Element element = new Element("at0001", new DvText("Test Element"), new DvCodedText("Test Value", codePhrase));

        ItemTree itemTree = new ItemTree("at0002", new DvText("Tree"), List.of(element));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0003");
        event.setName(new DvText("Event"));
        event.setData(itemTree);

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0004");
        history.setName(new DvText("History"));
        history.setEvents(List.of(event));

        Observation observation = new Observation();
        observation.setArchetypeNodeId("at0005");
        observation.setName(new DvText("Observation"));
        observation.setData(history);
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setSubject(new com.nedap.archie.rm.generic.PartySelf());

        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setContent(List.of(observation));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));
        composition.setCategory(new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433")));

        return composition;
    }

    @SuppressWarnings("unchecked")
    private static Composition compositionWithMultiple(CodePhrase code1, CodePhrase code2) {
        Element element1 = new Element("at0001", new DvText("Element 1"), new DvCodedText("Value 1", code1));
        Element element2 = new Element("at0002", new DvText("Element 2"), new DvCodedText("Value 2", code2));

        ItemTree itemTree = new ItemTree("at0003", new DvText("Tree"), List.of(element1, element2));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0004");
        event.setName(new DvText("Event"));
        event.setData(itemTree);

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0005");
        history.setName(new DvText("History"));
        history.setEvents(List.of(event));

        Observation observation = new Observation();
        observation.setArchetypeNodeId("at0006");
        observation.setName(new DvText("Observation"));
        observation.setData(history);
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setSubject(new com.nedap.archie.rm.generic.PartySelf());

        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setContent(List.of(observation));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));
        composition.setCategory(new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433")));

        return composition;
    }
}
