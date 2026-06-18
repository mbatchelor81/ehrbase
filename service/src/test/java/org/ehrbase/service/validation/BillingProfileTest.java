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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BillingProfileTest {

    @Test
    void billingProfile_defaultValues() {
        BillingProfile profile = new BillingProfile();

        assertThat(profile.isEnabled()).isTrue();
        assertThat(profile.getRequiredCodeSystems()).isEmpty();
    }

    @Test
    void billingProfile_setAndGetValues() {
        BillingProfile profile = new BillingProfile();
        profile.setEnabled(false);
        profile.setRequiredCodeSystems(List.of(
                "http://hl7.org/fhir/sid/icd-10-cm", "http://www.ama-assn.org/go/cpt", "http://snomed.info/sct"));

        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.getRequiredCodeSystems())
                .hasSize(3)
                .contains(
                        "http://hl7.org/fhir/sid/icd-10-cm",
                        "http://www.ama-assn.org/go/cpt",
                        "http://snomed.info/sct");
    }
}
