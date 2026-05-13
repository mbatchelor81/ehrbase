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
package org.ehrbase.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.ehrbase.api.exception.StateConflictException;
import org.ehrbase.api.service.StoredQueryService;
import org.junit.jupiter.api.Test;

class BillingQueryRegistrationServiceTest {

    private final StoredQueryService mockStoredQueryService = mock();

    @Test
    void registerBillingQueries_registersAllFourQueries() {
        BillingQueryRegistrationService service = new BillingQueryRegistrationService(mockStoredQueryService);

        service.registerBillingQueries();

        verify(mockStoredQueryService, times(4)).createStoredQuery(anyString(), anyString(), anyString(), eq("AQL"));
        verify(mockStoredQueryService)
                .createStoredQuery(
                        eq("billing::diagnosis-codes"),
                        eq("1.0.0"),
                        eq(BillingQueryDefinitions.DIAGNOSIS_CODES_AQL),
                        eq("AQL"));
        verify(mockStoredQueryService)
                .createStoredQuery(
                        eq("billing::procedure-codes"),
                        eq("1.0.0"),
                        eq(BillingQueryDefinitions.PROCEDURE_CODES_AQL),
                        eq("AQL"));
        verify(mockStoredQueryService)
                .createStoredQuery(
                        eq("billing::encounter-summary"),
                        eq("1.0.0"),
                        eq(BillingQueryDefinitions.ENCOUNTER_SUMMARY_AQL),
                        eq("AQL"));
        verify(mockStoredQueryService)
                .createStoredQuery(
                        eq("billing::patient-coverage"),
                        eq("1.0.0"),
                        eq(BillingQueryDefinitions.PATIENT_COVERAGE_AQL),
                        eq("AQL"));
    }

    @Test
    void registerBillingQueries_skipsAlreadyRegisteredQueries() {
        doThrow(new StateConflictException("Version already exists"))
                .when(mockStoredQueryService)
                .createStoredQuery(anyString(), anyString(), anyString(), anyString());

        BillingQueryRegistrationService service = new BillingQueryRegistrationService(mockStoredQueryService);

        service.registerBillingQueries();

        verify(mockStoredQueryService, times(4)).createStoredQuery(anyString(), anyString(), anyString(), eq("AQL"));
    }

    @Test
    void registerBillingQueries_continuesOnRuntimeException() {
        doThrow(new RuntimeException("DB unavailable"))
                .when(mockStoredQueryService)
                .createStoredQuery(eq("billing::diagnosis-codes"), anyString(), anyString(), anyString());

        BillingQueryRegistrationService service = new BillingQueryRegistrationService(mockStoredQueryService);

        service.registerBillingQueries();

        verify(mockStoredQueryService, times(4)).createStoredQuery(anyString(), anyString(), anyString(), eq("AQL"));
    }
}
