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

import java.util.List;
import java.util.UUID;

/**
 * Service interface for mapping openEHR data to FHIR R4 ExplanationOfBenefit resources.
 * The result is returned as pre-serialized JSON strings to avoid exposing HAPI FHIR
 * model types across module boundaries (rest-openehr depends on api, not service).
 */
public interface FhirEobService {

    /**
     * Maps openEHR composition data to FHIR R4 ExplanationOfBenefit JSON representations.
     *
     * @param ehrId the EHR UUID (used as patient identifier)
     * @return list of EOB resources as JSON strings; empty if the EHR is not queryable or has no data
     * @throws IllegalArgumentException if billing codes fail validation (EM-51 integration)
     */
    List<String> getEobJsonForEhr(UUID ehrId);
}
