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

import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Entry;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Cluster;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datavalues.DvCodedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates composition coded entries against required billing code systems defined in billing profiles.
 *
 * <p>Cross-references coded entries within a composition against the required code systems
 * configured for a given billing profile, using the existing {@link FhirTerminologyValidation}
 * infrastructure for actual code validation.
 */
public class BillingCodeValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BillingCodeValidator.class);

    private final FhirTerminologyValidation fhirTerminologyValidation;
    private final Map<String, BillingProfile> billingProfiles;

    public BillingCodeValidator(
            FhirTerminologyValidation fhirTerminologyValidation, Map<String, BillingProfile> billingProfiles) {
        this.fhirTerminologyValidation = fhirTerminologyValidation;
        this.billingProfiles = billingProfiles;
    }

    /**
     * Validates the given composition against the specified billing profile.
     *
     * @param composition the composition to validate
     * @param profileName the name of the billing profile to validate against
     * @throws ConstraintViolationException if any billing code validation fails
     * @throws IllegalArgumentException if the billing profile is not found
     */
    public void validate(Composition composition, String profileName) {
        BillingProfile profile = billingProfiles.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException("Billing profile not found: " + profileName);
        }
        if (!profile.isEnabled()) {
            LOG.debug("Billing profile '{}' is disabled, skipping validation", profileName);
            return;
        }

        List<DvCodedText> codedEntries = extractCodedEntries(composition);
        List<ConstraintViolation> violations = validateAgainstProfile(codedEntries, profile);

        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /**
     * Validates the given composition against all enabled billing profiles.
     *
     * @param composition the composition to validate
     * @throws ConstraintViolationException if any billing code validation fails
     */
    public void validateAll(Composition composition) {
        List<ConstraintViolation> allViolations = new ArrayList<>();
        List<DvCodedText> codedEntries = extractCodedEntries(composition);

        for (Map.Entry<String, BillingProfile> entry : billingProfiles.entrySet()) {
            if (!entry.getValue().isEnabled()) {
                LOG.debug("Billing profile '{}' is disabled, skipping", entry.getKey());
                continue;
            }

            allViolations.addAll(validateAgainstProfile(codedEntries, entry.getValue()));
        }

        if (!allViolations.isEmpty()) {
            throw new ConstraintViolationException(allViolations);
        }
    }

    List<DvCodedText> extractCodedEntries(Composition composition) {
        List<DvCodedText> codedEntries = new ArrayList<>();
        if (composition.getContent() != null) {
            composition.getContent().forEach(contentItem -> collectFromContentItem(contentItem, codedEntries));
        }
        return codedEntries;
    }

    private void collectFromContentItem(ContentItem contentItem, List<DvCodedText> codedEntries) {
        if (contentItem instanceof Section section) {
            if (section.getItems() != null) {
                section.getItems().forEach(item -> collectFromContentItem(item, codedEntries));
            }
        } else if (contentItem instanceof Entry entry) {
            collectFromEntry(entry, codedEntries);
        }
    }

    private void collectFromEntry(Entry entry, List<DvCodedText> codedEntries) {
        if (entry instanceof Observation observation) {
            collectFromHistory(observation.getData(), codedEntries);
            collectFromHistory(observation.getState(), codedEntries);
        } else if (entry instanceof Evaluation evaluation) {
            collectFromItemStructure(evaluation.getData(), codedEntries);
        } else if (entry instanceof Instruction instruction) {
            if (instruction.getActivities() != null) {
                instruction.getActivities().forEach(activity -> {
                    if (activity.getDescription() != null) {
                        collectFromItemStructure(activity.getDescription(), codedEntries);
                    }
                });
            }
        } else if (entry instanceof Action action) {
            collectFromItemStructure(action.getDescription(), codedEntries);
        } else if (entry instanceof AdminEntry adminEntry) {
            collectFromItemStructure(adminEntry.getData(), codedEntries);
        }
    }

    private void collectFromHistory(History<ItemStructure> history, List<DvCodedText> codedEntries) {
        if (history == null) {
            return;
        }
        if (history.getEvents() != null) {
            history.getEvents().forEach(event -> {
                if (event.getData() != null) {
                    collectFromItemStructure(event.getData(), codedEntries);
                }
            });
        }
        if (history.getSummary() != null) {
            collectFromItemStructure(history.getSummary(), codedEntries);
        }
    }

    private void collectFromItemStructure(ItemStructure itemStructure, List<DvCodedText> codedEntries) {
        if (itemStructure == null || itemStructure.getItems() == null) {
            return;
        }
        itemStructure.getItems().forEach(item -> collectFromItem(item, codedEntries));
    }

    private void collectFromItem(Object item, List<DvCodedText> codedEntries) {
        if (item instanceof Element element) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                codedEntries.add(dvCodedText);
            }
        } else if (item instanceof Cluster cluster) {
            if (cluster.getItems() != null) {
                cluster.getItems().forEach(child -> collectFromItem(child, codedEntries));
            }
        }
    }

    private List<ConstraintViolation> validateAgainstProfile(List<DvCodedText> codedEntries, BillingProfile profile) {
        List<TerminologyParam> paramsToValidate = new ArrayList<>();

        for (DvCodedText codedEntry : codedEntries) {
            if (codedEntry.getDefiningCode() == null
                    || codedEntry.getDefiningCode().getTerminologyId() == null) {
                continue;
            }

            String terminologyId =
                    codedEntry.getDefiningCode().getTerminologyId().getValue();

            for (String requiredCodeSystem : profile.getRequiredCodeSystems()) {
                if (requiredCodeSystem.equals(terminologyId)) {
                    TerminologyParam param =
                            TerminologyParam.ofFhir("//fhir.hl7.org/CodeSystem?url=" + requiredCodeSystem);
                    param.setCodePhrase(codedEntry.getDefiningCode());
                    paramsToValidate.add(param);
                }
            }
        }

        return fhirTerminologyValidation.batchValidate(paramsToValidate);
    }
}
