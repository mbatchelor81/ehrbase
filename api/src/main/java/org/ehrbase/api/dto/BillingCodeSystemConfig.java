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
package org.ehrbase.api.dto;

import java.util.List;

/**
 * Holds code system URI lists derived from billing profiles, used to classify
 * codes as diagnosis, procedure, or supporting in FHIR EOB mapping.
 */
public record BillingCodeSystemConfig(
        List<String> diagnosisCodeSystems, List<String> procedureCodeSystems, List<String> supportingCodeSystems) {

    public static BillingCodeSystemConfig defaults() {
        return new BillingCodeSystemConfig(
                List.of("http://hl7.org/fhir/sid/icd-10"), List.of("http://www.ama-assn.org/go/cpt"), List.of());
    }
}
