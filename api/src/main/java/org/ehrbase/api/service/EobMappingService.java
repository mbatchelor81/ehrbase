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
package org.ehrbase.api.service;

import com.nedap.archie.rm.composition.Composition;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;

/**
 * Service for mapping openEHR Compositions to FHIR R4 ExplanationOfBenefit resources.
 */
public interface EobMappingService {

    /**
     * Maps an openEHR Composition to a FHIR R4 ExplanationOfBenefit resource.
     *
     * @param ehrId       the EHR identifier used as the patient reference
     * @param composition the openEHR Composition to map
     * @return an ExplanationOfBenefit resource
     */
    ExplanationOfBenefit mapToEob(UUID ehrId, Composition composition);

    /**
     * Retrieves and maps all compositions for a given EHR to ExplanationOfBenefit resources.
     * Routes through CompositionService to enforce access controls.
     *
     * @param ehrId  the EHR identifier (patient reference)
     * @param offset pagination offset
     * @param count  maximum number of results to return
     * @return list of ExplanationOfBenefit resources
     */
    List<ExplanationOfBenefit> getExplanationOfBenefits(UUID ehrId, int offset, int count);
}
