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
package org.ehrbase.service.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a billing code validation profile containing a set of required code system URIs.
 *
 * <p>When enabled, compositions validated against this profile must contain coded entries
 * from the specified code systems that are valid according to the configured FHIR terminology server.
 */
public class BillingProfile {

    private boolean enabled = true;

    private List<String> requiredCodeSystems = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRequiredCodeSystems() {
        return requiredCodeSystems;
    }

    public void setRequiredCodeSystems(List<String> requiredCodeSystems) {
        this.requiredCodeSystems = requiredCodeSystems;
    }
}
