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
package org.ehrbase.rest.openehr;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.fhir.FhirEobService;
import org.ehrbase.rest.BaseController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * FHIR R4 ExplanationOfBenefit endpoint.
 * Maps openEHR composition data to FHIR EOB resources via {@link FhirEobService}.
 */
@ConditionalOnMissingBean(name = "primaryfhireobcontroller")
@RestController
@RequestMapping(path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/fhir/r4")
public class FhirEobController extends BaseController {

    static final String FHIR_JSON_MEDIA_TYPE = "application/fhir+json";
    static final MediaType APPLICATION_FHIR_JSON = MediaType.parseMediaType(FHIR_JSON_MEDIA_TYPE);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final FhirEobService fhirEobService;
    private final EhrService ehrService;

    public FhirEobController(FhirEobService fhirEobService, EhrService ehrService) {
        this.fhirEobService = Objects.requireNonNull(fhirEobService);
        this.ehrService = Objects.requireNonNull(ehrService);
    }

    @GetMapping(
            value = "/ExplanationOfBenefit",
            produces = {FHIR_JSON_MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> getExplanationOfBenefit(
            @RequestParam(value = "patient") String patientId,
            @RequestParam(value = "_count", required = false) Integer count,
            @RequestParam(value = "_offset", required = false) Integer offset,
            @RequestHeader(value = ACCEPT, required = false) String accept) {

        UUID ehrId = parsePatientId(patientId);
        ehrService.checkEhrExists(ehrId);

        List<String> allEobJsons;
        try {
            allEobJsons = fhirEobService.getEobJsonForEhr(ehrId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .contentType(APPLICATION_FHIR_JSON)
                    .body(buildOperationOutcome(e.getMessage()));
        }

        int pageSize = resolvePageSize(count);
        int pageOffset = (offset != null && offset >= 0) ? offset : 0;
        int total = allEobJsons.size();

        List<String> page = paginate(allEobJsons, pageOffset, pageSize);

        String bundleJson = buildBundleJson(page, total, pageOffset, pageSize);

        return ResponseEntity.ok().contentType(APPLICATION_FHIR_JSON).body(bundleJson);
    }

    private UUID parsePatientId(String patientId) {
        try {
            return UUID.fromString(patientId);
        } catch (IllegalArgumentException e) {
            throw new InvalidApiParameterException(
                    "Invalid patient ID format: '%s'. Expected UUID.".formatted(patientId));
        }
    }

    private int resolvePageSize(Integer count) {
        if (count == null || count <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(count, MAX_PAGE_SIZE);
    }

    private List<String> paginate(List<String> all, int offset, int pageSize) {
        if (offset >= all.size()) {
            return List.of();
        }
        int end = Math.min(offset + pageSize, all.size());
        return all.subList(offset, end);
    }

    private String buildBundleJson(List<String> pageEntries, int total, int offset, int pageSize) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentRequest()
                .replaceQueryParam("_offset")
                .replaceQueryParam("_count")
                .toUriString();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"resourceType\":\"Bundle\",");
        sb.append("\"type\":\"searchset\",");
        sb.append("\"total\":").append(total).append(",");

        sb.append("\"link\":[");
        sb.append("{\"relation\":\"self\",\"url\":\"")
                .append(buildPageUrl(baseUrl, offset, pageSize))
                .append("\"}");
        if (offset + pageSize < total) {
            sb.append(",{\"relation\":\"next\",\"url\":\"")
                    .append(buildPageUrl(baseUrl, offset + pageSize, pageSize))
                    .append("\"}");
        }
        if (offset > 0) {
            int prevOffset = Math.max(0, offset - pageSize);
            sb.append(",{\"relation\":\"previous\",\"url\":\"")
                    .append(buildPageUrl(baseUrl, prevOffset, pageSize))
                    .append("\"}");
        }
        sb.append("],");

        String entries = pageEntries.stream()
                .map(eobJson ->
                        "{\"fullUrl\":\"urn:uuid:" + UUID.randomUUID() + "\"," + "\"resource\":" + eobJson + "}")
                .collect(Collectors.joining(","));

        sb.append("\"entry\":[").append(entries).append("]");
        sb.append("}");

        return sb.toString();
    }

    private String buildPageUrl(String baseUrl, int offset, int pageSize) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "_offset=" + offset + "&_count=" + pageSize;
    }

    private String buildOperationOutcome(String message) {
        return """
                {
                  "resourceType": "OperationOutcome",
                  "issue": [{
                    "severity": "error",
                    "code": "invalid",
                    "diagnostics": "%s"
                  }]
                }""".formatted(message.replace("\"", "\\\""));
    }
}
