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
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.ehrbase.service.BillingQueryDefinitions.BillingQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BillingQueryDefinitionsTest {

    static List<BillingQuery> allQueries() {
        return BillingQueryDefinitions.all();
    }

    @ParameterizedTest
    @MethodSource("allQueries")
    void aqlSyntaxIsValid(BillingQuery query) {
        assertThatNoException()
                .as("AQL for %s should parse without errors", query.qualifiedName())
                .isThrownBy(() -> AqlQueryParser.parse(query.aql()));
    }

    @ParameterizedTest
    @MethodSource("allQueries")
    void queryNameFollowsBillingNamespace(BillingQuery query) {
        assertThat(query.qualifiedName()).startsWith("billing::");
    }

    @ParameterizedTest
    @MethodSource("allQueries")
    void queryVersionIsSemVer(BillingQuery query) {
        assertThat(query.version()).matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void allReturnsExactlyFourQueries() {
        assertThat(BillingQueryDefinitions.all()).hasSize(4);
    }

    @Test
    void diagnosisCodesQueryContainsExpectedFields() {
        assertThat(BillingQueryDefinitions.DIAGNOSIS_CODES_AQL)
                .contains("diagnosis_code", "icd10_code", "clinical_setting")
                .contains("$ehr_id");
    }

    @Test
    void procedureCodesQueryContainsExpectedFields() {
        assertThat(BillingQueryDefinitions.PROCEDURE_CODES_AQL)
                .contains("procedure_code", "date_of_service", "provider_reference")
                .contains("$ehr_id");
    }

    @Test
    void encounterSummaryQueryContainsExpectedFields() {
        assertThat(BillingQueryDefinitions.ENCOUNTER_SUMMARY_AQL)
                .contains("admission_date", "discharge_date", "encounter_type", "facility_name")
                .contains("$ehr_id");
    }

    @Test
    void patientCoverageQueryContainsExpectedFields() {
        assertThat(BillingQueryDefinitions.PATIENT_COVERAGE_AQL)
                .contains("patient_id", "insurance_id", "guarantor_name")
                .contains("$ehr_id");
    }

    @Test
    void queryNamesAreUnique() {
        List<String> names = BillingQueryDefinitions.all().stream()
                .map(BillingQuery::qualifiedName)
                .toList();
        assertThat(names).doesNotHaveDuplicates();
    }
}
