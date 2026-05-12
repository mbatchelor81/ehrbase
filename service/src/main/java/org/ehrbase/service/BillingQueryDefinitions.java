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

import java.util.List;

/**
 * Defines stored AQL queries for extracting billing-relevant clinical data from EHRbase compositions.
 * Each query follows the {@code billing::name/version} naming convention and is parameterized by EHR ID.
 */
public final class BillingQueryDefinitions {

    private BillingQueryDefinitions() {}

    public static final String QUERY_TYPE = "AQL";
    public static final String VERSION = "1.0.0";

    public static final String DIAGNOSIS_CODES_NAME = "billing::diagnosis-codes";
    public static final String DIAGNOSIS_CODES_AQL = """
            SELECT \
            e/ehr_id/value AS ehr_id, \
            eval/data[at0001]/items[at0002]/value/value AS diagnosis_code, \
            eval/data[at0001]/items[at0002]/value/defining_code/code_string AS icd10_code, \
            eval/data[at0001]/items[at0002]/value/defining_code/terminology_id/value AS coding_system, \
            eval/data[at0001]/items[at0009]/value/value AS date_of_onset, \
            c/context/setting/value AS clinical_setting \
            FROM EHR e \
            CONTAINS COMPOSITION c \
            CONTAINS EVALUATION eval \
            WHERE e/ehr_id/value = $ehr_id \
            ORDER BY eval/data[at0001]/items[at0009]/value/value DESC""";

    public static final String PROCEDURE_CODES_NAME = "billing::procedure-codes";
    public static final String PROCEDURE_CODES_AQL = """
            SELECT \
            e/ehr_id/value AS ehr_id, \
            act/description[at0001]/items[at0002]/value/value AS procedure_name, \
            act/description[at0001]/items[at0002]/value/defining_code/code_string AS procedure_code, \
            act/description[at0001]/items[at0002]/value/defining_code/terminology_id/value AS code_system, \
            act/time/value AS date_of_service, \
            act/description[at0001]/items[at0049]/value/value AS provider_reference \
            FROM EHR e \
            CONTAINS COMPOSITION c \
            CONTAINS ACTION act \
            WHERE e/ehr_id/value = $ehr_id \
            ORDER BY act/time/value DESC""";

    public static final String ENCOUNTER_SUMMARY_NAME = "billing::encounter-summary";
    public static final String ENCOUNTER_SUMMARY_AQL = """
            SELECT \
            e/ehr_id/value AS ehr_id, \
            c/context/start_time/value AS admission_date, \
            c/context/end_time/value AS discharge_date, \
            c/context/setting/value AS encounter_type, \
            c/context/health_care_facility/name AS facility_name, \
            c/context/participations/performer/name AS attending_provider, \
            c/uid/value AS composition_uid \
            FROM EHR e \
            CONTAINS COMPOSITION c \
            WHERE e/ehr_id/value = $ehr_id \
            ORDER BY c/context/start_time/value DESC""";

    public static final String PATIENT_COVERAGE_NAME = "billing::patient-coverage";
    public static final String PATIENT_COVERAGE_AQL = """
            SELECT \
            e/ehr_id/value AS ehr_id, \
            e/ehr_status/subject/external_ref/id/value AS patient_id, \
            e/ehr_status/subject/external_ref/namespace AS id_namespace, \
            cl/items[at0001]/value/value AS insurance_id, \
            cl/items[at0002]/value/value AS insurance_name, \
            cl/items[at0003]/value/value AS policy_number, \
            cl/items[at0004]/value/value AS guarantor_name \
            FROM EHR e \
            CONTAINS COMPOSITION c \
            CONTAINS CLUSTER cl \
            WHERE e/ehr_id/value = $ehr_id""";

    public record BillingQuery(String qualifiedName, String version, String aql) {}

    public static List<BillingQuery> all() {
        return List.of(
                new BillingQuery(DIAGNOSIS_CODES_NAME, VERSION, DIAGNOSIS_CODES_AQL),
                new BillingQuery(PROCEDURE_CODES_NAME, VERSION, PROCEDURE_CODES_AQL),
                new BillingQuery(ENCOUNTER_SUMMARY_NAME, VERSION, ENCOUNTER_SUMMARY_AQL),
                new BillingQuery(PATIENT_COVERAGE_NAME, VERSION, PATIENT_COVERAGE_AQL));
    }
}
