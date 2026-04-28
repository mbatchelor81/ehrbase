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

import com.nedap.archie.rm.RMObject;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.ehrbase.configuration.config.validation.ExternalValidationProperties;
import org.ehrbase.configuration.config.validation.ExternalValidationProperties.BillingProfile;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates billing-related coded entries (ICD-10, CPT, HCPCS) within a {@link Composition}
 * against a configured FHIR terminology server.
 *
 * <p>Validation is opt-in per billing profile. If the requested profile does not exist or is
 * disabled, an empty list of violations is returned.
 */
public class BillingCodeValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BillingCodeValidator.class);

    private final ExternalTerminologyValidation terminologyValidation;
    private final ExternalValidationProperties properties;

    public BillingCodeValidator(
            ExternalTerminologyValidation terminologyValidation, ExternalValidationProperties properties) {
        this.terminologyValidation = terminologyValidation;
        this.properties = properties;
    }

    /**
     * Validates all billing-coded entries in the given composition against the FHIR terminology
     * server for the specified billing profile.
     *
     * @param composition the openEHR composition to validate
     * @param profileName the billing profile name (e.g., {@code "us-claims"})
     * @return a list of constraint violations for invalid billing codes; empty if the profile
     *     is not found, not enabled, or all codes are valid
     */
    public List<ConstraintViolation> validateComposition(Composition composition, String profileName) {
        if (!properties.isEnabled()) {
            LOG.debug("External terminology validation is disabled, skipping billing validation");
            return List.of();
        }

        BillingProfile profile = properties.getBillingProfiles().get(profileName);
        if (profile == null || !profile.isEnabled()) {
            LOG.debug("Billing profile '{}' is not configured or not enabled, skipping validation", profileName);
            return List.of();
        }

        Set<String> billingCodeSystems = Set.copyOf(profile.getCodeSystems());

        List<CodePhrase> billingCodes = new ArrayList<>();
        collectBillingCodes(composition, billingCodeSystems, billingCodes);

        if (billingCodes.isEmpty()) {
            return List.of();
        }

        List<TerminologyParam> params = billingCodes.stream()
                .map(cp -> {
                    String system = cp.getTerminologyId().getValue();
                    TerminologyParam tp = TerminologyParam.ofFhir("//fhir.hl7.org/CodeSystem?url=" + system);
                    tp.setCodePhrase(cp);
                    return tp;
                })
                .collect(Collectors.toList());

        if (terminologyValidation instanceof FhirTerminologyValidation fhirValidation) {
            return collectViolationsFromBatch(fhirValidation.validateBatch(params));
        }

        return collectViolationsSequentially(params);
    }

    private List<ConstraintViolation> collectViolationsFromBatch(
            Map<TerminologyParam, Try<Boolean, ConstraintViolationException>> batchResults) {
        List<ConstraintViolation> violations = new ArrayList<>();
        for (Map.Entry<TerminologyParam, Try<Boolean, ConstraintViolationException>> entry : batchResults.entrySet()) {
            addViolationsIfFailure(entry.getValue(), violations);
        }
        return violations;
    }

    private List<ConstraintViolation> collectViolationsSequentially(List<TerminologyParam> params) {
        List<ConstraintViolation> violations = new ArrayList<>();
        for (TerminologyParam param : params) {
            addViolationsIfFailure(terminologyValidation.validate(param), violations);
        }
        return violations;
    }

    private static void addViolationsIfFailure(
            Try<Boolean, ConstraintViolationException> result, List<ConstraintViolation> violations) {
        if (result.isFailure()) {
            violations.addAll(result.getAsFailure().get().getConstraintViolations());
        }
    }

    private void collectBillingCodes(Object rmObject, Set<String> billingCodeSystems, List<CodePhrase> result) {
        if (rmObject == null) {
            return;
        }

        if (rmObject instanceof DvCodedText dvCodedText) {
            CodePhrase definingCode = dvCodedText.getDefiningCode();
            if (definingCode != null && isBillingCode(definingCode, billingCodeSystems)) {
                result.add(definingCode);
            }
            return;
        }

        if (rmObject instanceof RMObject rm) {
            for (Object child : collectChildren(rm)) {
                collectBillingCodes(child, billingCodeSystems, result);
            }
        }
    }

    private boolean isBillingCode(CodePhrase codePhrase, Set<String> billingCodeSystems) {
        return codePhrase.getTerminologyId() != null
                && billingCodeSystems.contains(codePhrase.getTerminologyId().getValue());
    }

    @SuppressWarnings("unchecked")
    private List<Object> collectChildren(RMObject rmObject) {
        List<Object> children = new ArrayList<>();
        for (java.lang.reflect.Method method : rmObject.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (!name.startsWith("get") || name.equals("getClass") || name.equals("getParent")) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (!RMObject.class.isAssignableFrom(returnType) && !Collection.class.isAssignableFrom(returnType)) {
                continue;
            }
            try {
                Object value = method.invoke(rmObject);
                if (value instanceof Collection<?> collection) {
                    for (Object item : collection) {
                        if (item instanceof RMObject) {
                            children.add(item);
                        }
                    }
                } else if (value instanceof RMObject) {
                    children.add(value);
                }
            } catch (ReflectiveOperationException e) {
                LOG.trace("Could not invoke getter {}: {}", name, e.getMessage());
            }
        }
        return children;
    }
}
