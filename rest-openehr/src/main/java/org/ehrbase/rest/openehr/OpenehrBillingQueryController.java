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

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.ehrbase.api.service.StoredQueryService;
import org.ehrbase.openehr.sdk.response.dto.QueryDefinitionResponseData;
import org.ehrbase.rest.BaseController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for discovering billing-specific stored AQL queries.
 * Lists all stored queries registered under the {@code billing} namespace.
 */
@Tag(name = "BILLING")
@RestController
@RequestMapping(
        path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/billing/queries",
        produces = APPLICATION_JSON_VALUE)
public class OpenehrBillingQueryController extends BaseController {

    private static final String BILLING_NAMESPACE = "billing";

    private final StoredQueryService storedQueryService;

    public OpenehrBillingQueryController(StoredQueryService storedQueryService) {
        this.storedQueryService = storedQueryService;
    }

    @Operation(
            summary = "List available billing queries",
            description = "Returns all stored AQL queries in the billing namespace")
    @GetMapping
    public ResponseEntity<List<QueryDefinitionResponseData>> listBillingQueries() {
        List<QueryDefinitionResponseData> billingQueries =
                storedQueryService.retrieveStoredQueries(BILLING_NAMESPACE).stream()
                        .map(QueryDefinitionResponseData::new)
                        .toList();
        return ResponseEntity.ok(billingQueries);
    }
}
