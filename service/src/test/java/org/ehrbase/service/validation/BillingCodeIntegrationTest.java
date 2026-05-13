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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test using a mock FHIR terminology server to validate billing code scenarios
 * end-to-end through the {@link BillingCodeValidator} and {@link FhirTerminologyValidation}.
 */
class BillingCodeIntegrationTest {

    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";

    private BillingCodeValidator validator;

    @BeforeEach
    void setUp() {
        FhirTerminologyValidation fhirValidation = new MockFhirTerminologyServer("http://mock-fhir-server");

        Map<String, BillingProfile> profiles = new HashMap<>();
        BillingProfile usClaimsProfile = new BillingProfile();
        usClaimsProfile.setEnabled(true);
        usClaimsProfile.setRequiredCodeSystems(List.of(ICD10_SYSTEM, CPT_SYSTEM, SNOMED_SYSTEM));
        profiles.put("us-claims", usClaimsProfile);

        validator = new BillingCodeValidator(fhirValidation, profiles);
    }

    @Test
    void endToEnd_validIcd10Diagnosis_passesValidation() {
        Composition composition = buildComposition(ICD10_SYSTEM, "E11.9", "Type 2 diabetes mellitus");

        validator.validate(composition, "us-claims");
    }

    @Test
    void endToEnd_validCptProcedure_passesValidation() {
        Composition composition = buildComposition(CPT_SYSTEM, "99213", "Office visit, level 3");

        validator.validate(composition, "us-claims");
    }

    @Test
    void endToEnd_validSnomedFinding_passesValidation() {
        Composition composition = buildComposition(SNOMED_SYSTEM, "73211009", "Diabetes mellitus");

        validator.validate(composition, "us-claims");
    }

    @Test
    void endToEnd_invalidIcd10Code_failsWithStructuredError() {
        Composition composition = buildComposition(ICD10_SYSTEM, "INVALID_ICD", "Bad diagnosis");

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .hasSize(1)
                .first()
                .extracting(Object::toString)
                .asString()
                .contains("INVALID_ICD")
                .contains(ICD10_SYSTEM);
    }

    @Test
    void endToEnd_invalidCptCode_failsWithStructuredError() {
        Composition composition = buildComposition(CPT_SYSTEM, "INVALID_CPT", "Bad procedure");

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .first()
                .extracting(Object::toString)
                .asString()
                .contains("INVALID_CPT")
                .contains(CPT_SYSTEM);
    }

    @Test
    void endToEnd_invalidSnomedCode_failsWithStructuredError() {
        Composition composition = buildComposition(SNOMED_SYSTEM, "INVALID_SCT", "Bad finding");

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .first()
                .extracting(Object::toString)
                .asString()
                .contains("INVALID_SCT")
                .contains(SNOMED_SYSTEM);
    }

    @Test
    void endToEnd_mixedBillingCodes_validatesAllCodeSystems() {
        Composition composition = buildCompositionWithMultipleCodes(
                List.of(ICD10_SYSTEM, CPT_SYSTEM, SNOMED_SYSTEM),
                List.of("E11.9", "99213", "73211009"),
                List.of("Diabetes", "Office visit", "Diabetes mellitus"));

        validator.validate(composition, "us-claims");
    }

    @Test
    void endToEnd_mixedValidAndInvalid_reportsAllFailures() {
        Composition composition = buildCompositionWithMultipleCodes(
                List.of(ICD10_SYSTEM, CPT_SYSTEM),
                List.of("E11.9", "INVALID_CPT"),
                List.of("Diabetes", "Bad procedure"));

        assertThatThrownBy(() -> validator.validate(composition, "us-claims"))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(t -> ((ConstraintViolationException) t).getConstraintViolations())
                .asList()
                .hasSize(1)
                .first()
                .extracting(Object::toString)
                .asString()
                .contains("INVALID_CPT");
    }

    @Test
    void endToEnd_nonBillingCode_ignored() {
        Composition composition = buildComposition("local", "at0001", "Local coded entry");

        validator.validate(composition, "us-claims");
    }

    /**
     * Mock FHIR terminology server that simulates CodeSystem/$validate-code responses.
     * Returns valid=true for known codes and valid=false for unknown codes.
     */
    private static class MockFhirTerminologyServer extends FhirTerminologyValidation {

        private static final Map<String, List<String>> KNOWN_CODES = Map.of(
                ICD10_SYSTEM, List.of("E11.9", "J06.9", "I10", "M54.5"),
                CPT_SYSTEM, List.of("99213", "99214", "99215", "99203"),
                SNOMED_SYSTEM, List.of("73211009", "38341003", "44054006", "386661006"));

        MockFhirTerminologyServer(String baseUrl) {
            super(baseUrl, true);
        }

        @Override
        protected DocumentContext internalGet(String uri) {
            if (uri.contains("CodeSystem/$validate-code")) {
                return handleValidateCode(uri);
            } else if (uri.contains("CodeSystem?url=")) {
                return handleCodeSystemLookup(uri);
            }
            return JsonPath.parse("{\"total\": 0}");
        }

        private DocumentContext handleValidateCode(String uri) {
            String code = extractParam(uri, "code=");
            String system = extractParam(uri, "url=");

            List<String> knownCodes = KNOWN_CODES.getOrDefault(system, List.of());
            boolean isValid = knownCodes.contains(code);

            if (isValid) {
                return JsonPath.parse("{\"parameter\": [{\"name\": \"result\", \"valueBoolean\": true}]}");
            } else {
                return JsonPath.parse("{\"parameter\": [{\"name\": \"result\", \"valueBoolean\": false},"
                        + "{\"name\": \"message\", \"valueString\": \"The specified code '"
                        + code
                        + "' is not known to belong to the specified code system '"
                        + system
                        + "'\"}]}");
            }
        }

        private DocumentContext handleCodeSystemLookup(String uri) {
            String system = extractParam(uri, "url=");
            boolean exists = KNOWN_CODES.containsKey(system);
            return JsonPath.parse("{\"total\": " + (exists ? 1 : 0) + "}");
        }

        private String extractParam(String uri, String paramName) {
            int start = uri.indexOf(paramName);
            if (start == -1) {
                return "";
            }
            start += paramName.length();
            int end = uri.indexOf('&', start);
            return end == -1 ? uri.substring(start) : uri.substring(start, end);
        }
    }

    private Composition buildComposition(String system, String code, String display) {
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

    private Composition buildCompositionWithMultipleCodes(
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
