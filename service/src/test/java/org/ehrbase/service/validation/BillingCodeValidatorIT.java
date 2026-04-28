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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.jayway.jsonpath.JsonPath;
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
import org.ehrbase.configuration.config.validation.ExternalValidationProperties;
import org.ehrbase.configuration.config.validation.ExternalValidationProperties.BillingProfile;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link BillingCodeValidator} using a spied {@link FhirTerminologyValidation}
 * with mocked FHIR terminology server responses.
 */
class BillingCodeValidatorIT {

    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";
    private static final String PROFILE_NAME = "us-claims";

    private FhirTerminologyValidation fhirValidation;
    private ExternalValidationProperties properties;
    private BillingCodeValidator validator;

    @BeforeEach
    void setUp() {
        fhirValidation = spy(new FhirTerminologyValidation("http://mock-fhir-server.local/fhir", true));
        properties = new ExternalValidationProperties();

        BillingProfile profile = new BillingProfile();
        profile.setName(PROFILE_NAME);
        profile.setEnabled(true);
        profile.setCodeSystems(List.of(ICD10_SYSTEM, CPT_SYSTEM));
        properties.getBillingProfiles().put(PROFILE_NAME, profile);

        validator = new BillingCodeValidator(fhirValidation, properties);
    }

    @Test
    void validIcd10Code_endToEnd() {
        String validateCodeResponse = """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    { "name": "result", "valueBoolean": true }
                  ]
                }
                """;

        doReturn(JsonPath.parse(validateCodeResponse))
                .when(fhirValidation)
                .internalGet(contains("CodeSystem/$validate-code"));

        Composition composition = compositionWithCodes(codePhrase(ICD10_SYSTEM, "E11.9"));

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertThat(violations).isEmpty();
    }

    @Test
    void invalidIcd10Code_endToEnd() {
        String validateCodeResponse = """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    { "name": "result", "valueBoolean": false },
                    { "name": "message", "valueString": "The specified code 'INVALID' is not known to belong to the specified code system 'http://hl7.org/fhir/sid/icd-10-cm'" }
                  ]
                }
                """;

        doReturn(JsonPath.parse(validateCodeResponse))
                .when(fhirValidation)
                .internalGet(contains("CodeSystem/$validate-code"));

        Composition composition = compositionWithCodes(codePhrase(ICD10_SYSTEM, "INVALID"));

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage()).contains("INVALID");
    }

    @Test
    void mixedBillingAndNonBillingCodes_onlyBillingCodesValidated() {
        String icdValidResponse = """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    { "name": "result", "valueBoolean": true }
                  ]
                }
                """;

        doReturn(JsonPath.parse(icdValidResponse))
                .when(fhirValidation)
                .internalGet(contains("CodeSystem/$validate-code"));

        Composition composition =
                compositionWithCodes(codePhrase(ICD10_SYSTEM, "E11.9"), codePhrase(SNOMED_SYSTEM, "73211009"));

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertThat(violations).isEmpty();
    }

    @Test
    void invalidCptCode_endToEnd() {
        String cptInvalidResponse = """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    { "name": "result", "valueBoolean": false },
                    { "name": "message", "valueString": "The specified code 'XXXXX' is not known to belong to the specified code system 'http://www.ama-assn.org/go/cpt'" }
                  ]
                }
                """;

        doReturn(JsonPath.parse(cptInvalidResponse))
                .when(fhirValidation)
                .internalGet(contains("CodeSystem/$validate-code"));

        Composition composition = compositionWithCodes(codePhrase(CPT_SYSTEM, "XXXXX"));

        List<ConstraintViolation> violations = validator.validateComposition(composition, PROFILE_NAME);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage()).contains("XXXXX");
    }

    private static CodePhrase codePhrase(String system, String code) {
        return new CodePhrase(new TerminologyId(system), code);
    }

    private static Composition compositionWithCodes(CodePhrase... codePhrases) {
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
}
