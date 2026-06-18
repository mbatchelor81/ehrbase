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
package org.ehrbase.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryDefinitionResultDto;
import org.ehrbase.test.ServiceIntegrationTest;
import org.ehrbase.util.SemVer;
import org.ehrbase.util.StoredQueryQualifiedName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test validating that V28__seed_billing_stored_queries.sql
 * correctly seeds the four billing stored queries and they are retrievable
 * through StoredQueryRepository.
 */
@ServiceIntegrationTest
class BillingStoredQueryRegistrationIT {

    private static final String REVERSE_DOMAIN = "org.ehrbase.fhir.billing";
    private static final String VERSION = "1.0.0";

    @Autowired
    StoredQueryRepository storedQueryRepository;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "billing-encounters",
                "billing-diagnoses",
                "billing-procedures",
                "billing-medication-orders"
            })
    void billingQueryIsRegisteredAndRetrievable(String semanticId) {
        StoredQueryQualifiedName qualifiedName =
                StoredQueryQualifiedName.create(REVERSE_DOMAIN + "::" + semanticId, SemVer.parse(VERSION));

        var result = storedQueryRepository.retrieveQualified(qualifiedName);
        assertThat(result)
                .as("Billing query '%s' should be present after Flyway migration", semanticId)
                .isPresent();

        QueryDefinitionResultDto dto = result.get();
        assertThat(dto.getQualifiedName()).isEqualTo(REVERSE_DOMAIN + "::" + semanticId);
        assertThat(dto.getVersion()).isEqualTo(VERSION);
        assertThat(dto.getType()).isEqualTo("AQL");
        assertThat(dto.getQueryText()).isNotBlank();
        assertThat(dto.getQueryText()).contains("e/ehr_id/value = $ehr_id");
    }

    @Test
    void allFourBillingQueriesAppearInList() {
        List<QueryDefinitionResultDto> results =
                storedQueryRepository.retrieveQualifiedList(REVERSE_DOMAIN + "::");

        assertThat(results)
                .as("All four billing queries should be listed under reverse domain '%s'", REVERSE_DOMAIN)
                .hasSizeGreaterThanOrEqualTo(4);

        assertThat(results.stream().map(QueryDefinitionResultDto::getQualifiedName))
                .contains(
                        REVERSE_DOMAIN + "::billing-encounters",
                        REVERSE_DOMAIN + "::billing-diagnoses",
                        REVERSE_DOMAIN + "::billing-procedures",
                        REVERSE_DOMAIN + "::billing-medication-orders");
    }
}
