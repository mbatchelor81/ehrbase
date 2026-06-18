/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- EM-53: Seed stored AQL queries for billing data extraction.
-- Inserts four billing-related stored queries into the stored_query table.
-- Primary key is (reverse_domain_name, semantic_id, semver) after V5_2.
-- Column sys_tenant was dropped in V5_3.
-- Uses ON CONFLICT to be idempotent (safe to re-run).

INSERT INTO stored_query (reverse_domain_name, semantic_id, semver, query_text, type, creation_date)
VALUES (
    'org.ehrbase.fhir.billing',
    'billing-encounters',
    '1.0.0',
    'SELECT e/ehr_id/value AS ehr_id, c/context/start_time/value AS admission_date, c/context/end_time/value AS discharge_date, c/context/setting/value AS encounter_type, c/context/health_care_facility/name AS facility_name, c/context/participations/performer/name AS attending_provider, c/uid/value AS composition_uid FROM EHR e CONTAINS COMPOSITION c WHERE e/ehr_id/value = $ehr_id ORDER BY c/context/start_time/value DESC',
    'AQL',
    NOW()
)
ON CONFLICT (reverse_domain_name, semantic_id, semver) DO NOTHING;

INSERT INTO stored_query (reverse_domain_name, semantic_id, semver, query_text, type, creation_date)
VALUES (
    'org.ehrbase.fhir.billing',
    'billing-diagnoses',
    '1.0.0',
    'SELECT e/ehr_id/value AS ehr_id, eval/data[at0001]/items[at0002]/value/value AS diagnosis, eval/data[at0001]/items[at0002]/value/defining_code/code_string AS icd10_code, eval/data[at0001]/items[at0002]/value/defining_code/terminology_id/value AS coding_system, eval/data[at0001]/items[at0009]/value/value AS date_of_onset, c/context/setting/value AS clinical_setting FROM EHR e CONTAINS COMPOSITION c CONTAINS EVALUATION eval WHERE e/ehr_id/value = $ehr_id ORDER BY eval/data[at0001]/items[at0009]/value/value DESC',
    'AQL',
    NOW()
)
ON CONFLICT (reverse_domain_name, semantic_id, semver) DO NOTHING;

INSERT INTO stored_query (reverse_domain_name, semantic_id, semver, query_text, type, creation_date)
VALUES (
    'org.ehrbase.fhir.billing',
    'billing-procedures',
    '1.0.0',
    'SELECT e/ehr_id/value AS ehr_id, act/description[at0001]/items[at0002]/value/value AS procedure_name, act/description[at0001]/items[at0002]/value/defining_code/code_string AS procedure_code, act/description[at0001]/items[at0002]/value/defining_code/terminology_id/value AS code_system, act/time/value AS date_of_service FROM EHR e CONTAINS COMPOSITION c CONTAINS ACTION act WHERE e/ehr_id/value = $ehr_id ORDER BY act/time/value DESC',
    'AQL',
    NOW()
)
ON CONFLICT (reverse_domain_name, semantic_id, semver) DO NOTHING;

INSERT INTO stored_query (reverse_domain_name, semantic_id, semver, query_text, type, creation_date)
VALUES (
    'org.ehrbase.fhir.billing',
    'billing-medication-orders',
    '1.0.0',
    'SELECT e/ehr_id/value AS ehr_id, inst/narrative/value AS medication_name, inst/activities[at0001]/description[at0002]/items[at0070]/value/value AS medication_item, inst/activities[at0001]/timing/value AS timing, c/context/start_time/value AS order_date FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION inst WHERE e/ehr_id/value = $ehr_id ORDER BY c/context/start_time/value DESC',
    'AQL',
    NOW()
)
ON CONFLICT (reverse_domain_name, semantic_id, semver) DO NOTHING;
