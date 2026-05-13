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

import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FhirTerminologyBatchValidationTest {

    @Test
    void batchValidate_allValid_returnsEmptyList() {
        FhirTerminologyValidation validation = Mockito.spy(new FhirTerminologyValidation("http://localhost:8080"));

        TerminologyParam param1 = createCodeSystemParam("http://hl7.org/fhir/sid/icd-10-cm", "E11.9");
        TerminologyParam param2 = createCodeSystemParam("http://www.ama-assn.org/go/cpt", "99213");

        Mockito.doReturn(Try.success(Boolean.TRUE)).when(validation).validate(param1);
        Mockito.doReturn(Try.success(Boolean.TRUE)).when(validation).validate(param2);

        List<ConstraintViolation> violations = validation.batchValidate(List.of(param1, param2));

        assertThat(violations).isEmpty();
    }

    @Test
    void batchValidate_oneInvalid_returnsViolation() {
        FhirTerminologyValidation validation = Mockito.spy(new FhirTerminologyValidation("http://localhost:8080"));

        TerminologyParam validParam = createCodeSystemParam("http://hl7.org/fhir/sid/icd-10-cm", "E11.9");
        TerminologyParam invalidParam = createCodeSystemParam("http://www.ama-assn.org/go/cpt", "INVALID");

        Mockito.doReturn(Try.success(Boolean.TRUE)).when(validation).validate(validParam);
        Mockito.doReturn(Try.failure(new ConstraintViolationException(List.of(new ConstraintViolation(
                        "Invalid code 'INVALID' in code system http://www.ama-assn.org/go/cpt")))))
                .when(validation)
                .validate(invalidParam);

        List<ConstraintViolation> violations = validation.batchValidate(List.of(validParam, invalidParam));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getMessage()).contains("INVALID");
    }

    @Test
    void batchValidate_multipleInvalid_returnsAllViolations() {
        FhirTerminologyValidation validation = Mockito.spy(new FhirTerminologyValidation("http://localhost:8080"));

        TerminologyParam invalidParam1 = createCodeSystemParam("http://hl7.org/fhir/sid/icd-10-cm", "BAD1");
        TerminologyParam invalidParam2 = createCodeSystemParam("http://www.ama-assn.org/go/cpt", "BAD2");
        TerminologyParam invalidParam3 = createCodeSystemParam("http://snomed.info/sct", "BAD3");

        Mockito.doReturn(Try.failure(new ConstraintViolationException(
                        List.of(new ConstraintViolation("ICD-10 validation failed for BAD1")))))
                .when(validation)
                .validate(invalidParam1);
        Mockito.doReturn(Try.failure(new ConstraintViolationException(
                        List.of(new ConstraintViolation("CPT validation failed for BAD2")))))
                .when(validation)
                .validate(invalidParam2);
        Mockito.doReturn(Try.failure(new ConstraintViolationException(
                        List.of(new ConstraintViolation("SNOMED validation failed for BAD3")))))
                .when(validation)
                .validate(invalidParam3);

        List<ConstraintViolation> violations =
                validation.batchValidate(List.of(invalidParam1, invalidParam2, invalidParam3));

        assertThat(violations).hasSize(3);
        assertThat(violations.get(0).getMessage()).contains("BAD1");
        assertThat(violations.get(1).getMessage()).contains("BAD2");
        assertThat(violations.get(2).getMessage()).contains("BAD3");
    }

    @Test
    void batchValidate_emptyList_returnsEmptyList() {
        FhirTerminologyValidation validation = new FhirTerminologyValidation("http://localhost:8080");

        List<ConstraintViolation> violations = validation.batchValidate(List.of());

        assertThat(violations).isEmpty();
    }

    private TerminologyParam createCodeSystemParam(String system, String code) {
        TerminologyParam param = TerminologyParam.ofFhir("//fhir.hl7.org/CodeSystem?url=" + system);
        param.setCodePhrase(new CodePhrase(new TerminologyId(system), code));
        return param;
    }
}
