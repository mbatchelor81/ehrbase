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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import org.ehrbase.api.service.StoredQueryService;
import org.ehrbase.openehr.sdk.response.dto.QueryDefinitionResponseData;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryDefinitionResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OpenehrBillingQueryControllerTest {

    private final StoredQueryService mockStoredQueryService = mock();
    private final OpenehrBillingQueryController spyController =
            spy(new OpenehrBillingQueryController(mockStoredQueryService));

    @BeforeEach
    void setUp() {
        Mockito.reset(mockStoredQueryService);
    }

    private QueryDefinitionResultDto resultDto(String name, String version, String query) {
        QueryDefinitionResultDto dto = new QueryDefinitionResultDto();
        dto.setQueryText(query);
        dto.setQualifiedName(name);
        dto.setVersion(version);
        dto.setType("AQL");
        dto.setSaved(ZonedDateTime.now());
        return dto;
    }

    @Test
    void listBillingQueries_returnsEmptyListWhenNoBillingQueriesRegistered() {
        doReturn(Collections.emptyList()).when(mockStoredQueryService).retrieveStoredQueries("billing");

        ResponseEntity<List<QueryDefinitionResponseData>> response = spyController.listBillingQueries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
        verify(mockStoredQueryService).retrieveStoredQueries("billing");
    }

    @Test
    void listBillingQueries_returnsRegisteredBillingQueries() {
        List<QueryDefinitionResultDto> dtos = List.of(
                resultDto("billing::diagnosis-codes", "1.0.0", "SELECT e/ehr_id/value FROM EHR e"),
                resultDto("billing::procedure-codes", "1.0.0", "SELECT e/ehr_id/value FROM EHR e"));

        doReturn(dtos).when(mockStoredQueryService).retrieveStoredQueries("billing");

        ResponseEntity<List<QueryDefinitionResponseData>> response = spyController.listBillingQueries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSize(2);
        assertThat(response.getBody().get(0).getName()).isEqualTo("billing::diagnosis-codes");
        assertThat(response.getBody().get(1).getName()).isEqualTo("billing::procedure-codes");
    }

    @Test
    void listBillingQueries_queriesWithBillingNamespace() {
        doReturn(Collections.emptyList()).when(mockStoredQueryService).retrieveStoredQueries("billing");

        spyController.listBillingQueries();

        verify(mockStoredQueryService).retrieveStoredQueries("billing");
    }
}
