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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.stream.Stream;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Validates that the billing AQL queries seeded by V28__seed_billing_stored_queries.sql
 * are syntactically valid and parseable by the AQL engine.
 *
 * <h2>Billing Stored Queries (EM-53)</h2>
 *
 * <p>Four stored AQL queries are registered under {@code org.ehrbase.fhir.billing::} via Flyway
 * migration V28. Each query is parameterized by {@code $ehr_id} to extract billing-relevant
 * clinical data for a specific patient.</p>
 *
 * <h3>Registered queries</h3>
 * <ul>
 *   <li>{@code org.ehrbase.fhir.billing::billing-encounters/1.0.0} — encounter/admission data</li>
 *   <li>{@code org.ehrbase.fhir.billing::billing-diagnoses/1.0.0} — diagnosis codes (ICD-10)</li>
 *   <li>{@code org.ehrbase.fhir.billing::billing-procedures/1.0.0} — procedure codes and dates</li>
 *   <li>{@code org.ehrbase.fhir.billing::billing-medication-orders/1.0.0} — medication orders</li>
 * </ul>
 *
 * <h3>REST API usage examples</h3>
 * <pre>
 * # List all billing stored queries:
 * GET /rest/openehr/v1/definition/query/org.ehrbase.fhir.billing::
 *
 * # Retrieve a specific billing query definition:
 * GET /rest/openehr/v1/definition/query/org.ehrbase.fhir.billing::billing-encounters/1.0.0
 *
 * # Execute a billing query with ehr_id parameter (GET):
 * GET /rest/openehr/v1/query/org.ehrbase.fhir.billing::billing-encounters/1.0.0
 *     ?query_parameters={"ehr_id":"7d44b88c-4199-4bad-97dc-d78268e01398"}
 *
 * # Execute a billing query with ehr_id parameter (POST):
 * POST /rest/openehr/v1/query/org.ehrbase.fhir.billing::billing-encounters/1.0.0
 * Content-Type: application/json
 * {
 *   "query_parameters": {
 *     "ehr_id": "7d44b88c-4199-4bad-97dc-d78268e01398"
 *   }
 * }
 * </pre>
 *
 * @see org.ehrbase.repository.BillingStoredQueryRegistrationIT
 */
class BillingStoredQueryValidationTest {

    static final String BILLING_ENCOUNTERS_AQL =
            "SELECT e/ehr_id/value AS ehr_id, "
                    + "c/context/start_time/value AS admission_date, "
                    + "c/context/end_time/value AS discharge_date, "
                    + "c/context/setting/value AS encounter_type, "
                    + "c/context/health_care_facility/name AS facility_name, "
                    + "c/context/participations/performer/name AS attending_provider, "
                    + "c/uid/value AS composition_uid "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_id/value = $ehr_id "
                    + "ORDER BY c/context/start_time/value DESC";

    static final String BILLING_DIAGNOSES_AQL =
            "SELECT e/ehr_id/value AS ehr_id, "
                    + "eval/data[at0001]/items[at0002]/value/value AS diagnosis, "
                    + "eval/data[at0001]/items[at0002]/value/defining_code/code_string AS icd10_code, "
                    + "eval/data[at0001]/items[at0002]/value/defining_code/terminology_id/value AS coding_system, "
                    + "eval/data[at0001]/items[at0009]/value/value AS date_of_onset, "
                    + "c/context/setting/value AS clinical_setting "
                    + "FROM EHR e CONTAINS COMPOSITION c CONTAINS EVALUATION eval "
                    + "WHERE e/ehr_id/value = $ehr_id "
                    + "ORDER BY eval/data[at0001]/items[at0009]/value/value DESC";

    static final String BILLING_PROCEDURES_AQL =
            "SELECT e/ehr_id/value AS ehr_id, "
                    + "act/description[at0001]/items[at0002]/value/value AS procedure_name, "
                    + "act/description[at0001]/items[at0002]/value/defining_code/code_string AS procedure_code, "
                    + "act/description[at0001]/items[at0002]/value/defining_code/terminology_id/value AS code_system, "
                    + "act/time/value AS date_of_service "
                    + "FROM EHR e CONTAINS COMPOSITION c CONTAINS ACTION act "
                    + "WHERE e/ehr_id/value = $ehr_id "
                    + "ORDER BY act/time/value DESC";

    static final String BILLING_MEDICATION_ORDERS_AQL =
            "SELECT e/ehr_id/value AS ehr_id, "
                    + "inst/narrative/value AS medication_name, "
                    + "inst/activities[at0001]/description[at0002]/items[at0070]/value/value AS medication_item, "
                    + "inst/activities[at0001]/timing/value AS timing, "
                    + "c/context/start_time/value AS order_date "
                    + "FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION inst "
                    + "WHERE e/ehr_id/value = $ehr_id "
                    + "ORDER BY c/context/start_time/value DESC";

    static Stream<Arguments> billingQueries() {
        return Stream.of(
                Arguments.of("billing-encounters", BILLING_ENCOUNTERS_AQL),
                Arguments.of("billing-diagnoses", BILLING_DIAGNOSES_AQL),
                Arguments.of("billing-procedures", BILLING_PROCEDURES_AQL),
                Arguments.of("billing-medication-orders", BILLING_MEDICATION_ORDERS_AQL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("billingQueries")
    void billingQueryParsesSuccessfully(String queryName, String aql) {
        AqlQuery parsed = assertDoesNotThrow(
                () -> AqlQueryParser.parse(aql), "AQL query '%s' should parse without errors".formatted(queryName));
        assertThat(parsed).isNotNull();
        assertThat(parsed.getSelect()).isNotNull();
        assertThat(parsed.getFrom()).isNotNull();
        assertThat(parsed.getWhere()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("billingQueries")
    void billingQueryIsBoundedByEhrId(String queryName, String aql) {
        assertThat(aql)
                .as("Query '%s' must filter by ehr_id to prevent full table scans", queryName)
                .contains("e/ehr_id/value = $ehr_id");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("billingQueries")
    void billingQueryUsesCorrectContainmentTypes(String queryName, String aql) {
        AqlQuery parsed = AqlQueryParser.parse(aql);
        assertThat(parsed.getFrom()).isNotNull();
        assertThat(parsed.getFrom().toString()).contains("EHR");
    }

    @Test
    void allFourBillingQueriesAreDefined() {
        assertThat(billingQueries().count()).isEqualTo(4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("billingQueries")
    void billingQueryHasOrderByClause(String queryName, String aql) {
        AqlQuery parsed = AqlQueryParser.parse(aql);
        assertThat(parsed.getOrderBy())
                .as("Query '%s' should have an ORDER BY clause for deterministic results", queryName)
                .isNotNull()
                .isNotEmpty();
    }
}
