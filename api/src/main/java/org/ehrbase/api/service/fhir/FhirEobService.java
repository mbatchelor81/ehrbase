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
package org.ehrbase.api.service.fhir;

import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;

/**
 * Service for retrieving FHIR R4 ExplanationOfBenefit resources mapped from openEHR compositions.
 */
public interface FhirEobService {

    /**
     * Retrieves ExplanationOfBenefit resources for the given EHR (patient).
     *
     * @param ehrId    the EHR UUID acting as the patient identifier
     * @param offset   pagination offset (0-based)
     * @param count    maximum number of entries per page
     * @param baseUrl  the base URL for constructing pagination links
     * @return a FHIR Bundle containing matching ExplanationOfBenefit entries
     */
    Bundle getExplanationOfBenefitForPatient(UUID ehrId, int offset, int count, String baseUrl);
}
