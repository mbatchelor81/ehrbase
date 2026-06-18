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
package org.ehrbase.service.fhir;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.ehrbase.openehr.sdk.response.dto.QueryResponseData;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleLinkComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link FhirEobService} that uses AQL queries to retrieve
 * openEHR composition data and maps it to FHIR R4 ExplanationOfBenefit resources.
 */
@Service
public class FhirEobServiceImp implements FhirEobService {

    private static final String EOB_AQL_TEMPLATE =
            "SELECT c/uid/value, c/archetype_details/template_id/value, c/context/start_time/value "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_id/value = '%s' "
                    + "ORDER BY c/context/start_time/value DESC";

    private static final String CODING_AQL_TEMPLATE = "SELECT c/uid/value, d/value/defining_code/terminology_id/value, "
            + "d/value/defining_code/code_string, d/value/value "
            + "FROM EHR e CONTAINS COMPOSITION c CONTAINS ELEMENT d "
            + "WHERE e/ehr_id/value = '%s' "
            + "AND d/value/defining_code/terminology_id/value != null";

    private final AqlQueryService aqlQueryService;
    private final EhrService ehrService;
    private final EobMappingService eobMappingService;

    public FhirEobServiceImp(
            AqlQueryService aqlQueryService, EhrService ehrService, EobMappingService eobMappingService) {
        this.aqlQueryService = aqlQueryService;
        this.ehrService = ehrService;
        this.eobMappingService = eobMappingService;
    }

    @Override
    public Bundle getExplanationOfBenefitForPatient(UUID ehrId, int offset, int count, String baseUrl) {
        ehrService.checkEhrExists(ehrId);

        if (!ehrService.isEhrQueryable(ehrId)) {
            return buildEmptyBundle(baseUrl);
        }

        List<List<Object>> compositionRows = queryCompositions(ehrId, offset, count);

        Map<String, List<Map<String, String>>> codingsByComposition = Map.of();
        if (!compositionRows.isEmpty()) {
            codingsByComposition = queryCodings(ehrId);
        }

        Bundle bundle = new Bundle();
        bundle.setType(BundleType.SEARCHSET);

        for (List<Object> row : compositionRows) {
            String uid = row.get(0) != null ? row.get(0).toString() : null;
            String templateId = row.get(1) != null ? row.get(1).toString() : null;
            Date startTime = parseDateTime(row.get(2));

            if (uid == null) {
                continue;
            }

            List<Map<String, String>> codings = codingsByComposition.getOrDefault(uid, List.of());
            ExplanationOfBenefit eob = eobMappingService.mapToEob(toUuid(uid), ehrId, templateId, startTime, codings);

            BundleEntryComponent entry = new BundleEntryComponent();
            entry.setResource(eob);
            entry.setFullUrl(baseUrl + "/ExplanationOfBenefit/" + uid);
            bundle.addEntry(entry);
        }

        bundle.setTotal(bundle.getEntry().size());

        if (compositionRows.size() == count) {
            int nextOffset = offset + count;
            BundleLinkComponent nextLink = new BundleLinkComponent();
            nextLink.setRelation("next");
            nextLink.setUrl(
                    baseUrl + "/ExplanationOfBenefit?patient=" + ehrId + "&_offset=" + nextOffset + "&_count=" + count);
            bundle.addLink(nextLink);
        }

        BundleLinkComponent selfLink = new BundleLinkComponent();
        selfLink.setRelation("self");
        selfLink.setUrl(baseUrl + "/ExplanationOfBenefit?patient=" + ehrId + "&_offset=" + offset + "&_count=" + count);
        bundle.addLink(selfLink);

        return bundle;
    }

    private Bundle buildEmptyBundle(String baseUrl) {
        Bundle bundle = new Bundle();
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }

    private List<List<Object>> queryCompositions(UUID ehrId, int offset, int count) {
        String aql = String.format(EOB_AQL_TEMPLATE, ehrId);
        AqlQueryRequest request = AqlQueryRequest.prepare(aql, Map.of(), (long) count, (long) offset);
        QueryResultDto resultDto = aqlQueryService.query(request);
        QueryResponseData response = new QueryResponseData(resultDto);
        return response.getRows() != null ? response.getRows() : List.of();
    }

    private Map<String, List<Map<String, String>>> queryCodings(UUID ehrId) {
        String aql = String.format(CODING_AQL_TEMPLATE, ehrId);
        AqlQueryRequest request = AqlQueryRequest.prepare(aql, Map.of(), null, null);
        QueryResultDto resultDto = aqlQueryService.query(request);
        QueryResponseData response = new QueryResponseData(resultDto);

        Map<String, List<Map<String, String>>> codingsByComposition = new HashMap<>();
        if (response.getRows() != null) {
            for (List<Object> row : response.getRows()) {
                String compositionUid = row.get(0) != null ? row.get(0).toString() : null;
                String system = row.get(1) != null ? row.get(1).toString() : null;
                String code = row.get(2) != null ? row.get(2).toString() : null;
                String display = row.get(3) != null ? row.get(3).toString() : null;

                if (compositionUid != null && system != null) {
                    Map<String, String> coding = new HashMap<>();
                    coding.put("system", system);
                    coding.put("code", code);
                    coding.put("display", display);
                    codingsByComposition
                            .computeIfAbsent(compositionUid, k -> new ArrayList<>())
                            .add(coding);
                }
            }
        }
        return codingsByComposition;
    }

    private static Date parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String str = value.toString();
            OffsetDateTime odt = OffsetDateTime.parse(str);
            return Date.from(odt.toInstant());
        } catch (Exception e) {
            return new Date();
        }
    }

    private static UUID toUuid(String uid) {
        String uuidPart = uid.contains("::") ? uid.substring(0, uid.indexOf("::")) : uid;
        try {
            return UUID.fromString(uuidPart);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(uid.getBytes());
        }
    }
}
