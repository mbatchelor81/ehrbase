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
package org.ehrbase.api.service.validation;

/**
 * Seam for billing-code validation (EM-51 integration point).
 *
 * EM-51 delivers the full implementation ({@code BillingCodeValidator} in the service module)
 * with ICD-10, CPT/HCPCS, and SNOMED CT validation. Until that branch is merged,
 * this interface is satisfied by {@code NoOpBillingCodeValidator} which accepts all codes.
 */
public interface BillingCodeValidator {

    String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10";
    String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    String HCPCS_SYSTEM = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    String SNOMED_SYSTEM = "http://snomed.info/sct";

    /**
     * Validates a billing code against its code system.
     *
     * @param codeSystem the code system URI (e.g. {@link #ICD10_SYSTEM})
     * @param code       the code value
     * @return validation result
     */
    ValidationResult validate(String codeSystem, String code);

    record ValidationResult(boolean valid, String message) {

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
