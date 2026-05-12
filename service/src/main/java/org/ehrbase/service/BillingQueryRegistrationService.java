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
import org.ehrbase.api.exception.StateConflictException;
import org.ehrbase.api.service.StoredQueryService;
import org.ehrbase.service.BillingQueryDefinitions.BillingQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers billing AQL stored queries on application startup.
 * Disabled by default; enable by setting {@code ehrbase.billing.queries.register-on-startup=true}.
 */
@Component
@ConditionalOnProperty(
        name = "ehrbase.billing.queries.register-on-startup",
        havingValue = "true",
        matchIfMissing = false)
public class BillingQueryRegistrationService implements ApplicationRunner {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final StoredQueryService storedQueryService;

    public BillingQueryRegistrationService(StoredQueryService storedQueryService) {
        this.storedQueryService = storedQueryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerBillingQueries();
    }

    void registerBillingQueries() {
        List<BillingQuery> queries = BillingQueryDefinitions.all();
        for (BillingQuery query : queries) {
            try {
                storedQueryService.createStoredQuery(
                        query.qualifiedName(), query.version(), query.aql(), BillingQueryDefinitions.QUERY_TYPE);
                logger.info("Registered billing query: {}/{}", query.qualifiedName(), query.version());
            } catch (StateConflictException e) {
                logger.debug("Billing query already registered: {}/{}", query.qualifiedName(), query.version());
            } catch (RuntimeException e) {
                logger.warn(
                        "Failed to register billing query {}/{}: {}",
                        query.qualifiedName(),
                        query.version(),
                        e.getMessage());
            }
        }
    }
}
