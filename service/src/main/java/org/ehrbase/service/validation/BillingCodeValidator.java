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
import com.nedap.archie.rm.composition.Activity;
import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Cluster;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Event;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemList;
import com.nedap.archie.rm.datastructures.ItemSingle;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTable;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates coded entries within a {@link Composition} against configured billing code systems,
 * delegating actual code checks to {@link FhirTerminologyValidation}.
 *
 * <h3>Canonical Billing Code System URLs</h3>
 * <ul>
 *   <li>{@link #ICD10_SYSTEM} — {@value #ICD10_SYSTEM}</li>
 *   <li>{@link #CPT_SYSTEM} — {@value #CPT_SYSTEM}</li>
 *   <li>{@link #HCPCS_SYSTEM} — {@value #HCPCS_SYSTEM}</li>
 *   <li>{@link #SNOMED_CT_SYSTEM} — {@value #SNOMED_CT_SYSTEM}</li>
 * </ul>
 *
 * <h3>Public API for EM-52</h3>
 * <pre>
 * Try&lt;Boolean, ConstraintViolationException&gt; validateComposition(Composition, String profileName)
 * Try&lt;Boolean, ConstraintViolationException&gt; validateCodedEntries(List&lt;CodePhrase&gt;, String profileName)
 * </pre>
 */
public class BillingCodeValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BillingCodeValidator.class);

    /** ICD-10-CM code system (diagnosis codes) */
    public static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";

    /** CPT code system (procedure codes) */
    public static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";

    /** HCPCS code system (supplies and services) */
    public static final String HCPCS_SYSTEM = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    /** SNOMED CT code system */
    public static final String SNOMED_CT_SYSTEM = "http://snomed.info/sct";

    private final FhirTerminologyValidation terminologyValidation;
    private final Map<String, BillingProfileConfig> profiles;

    public BillingCodeValidator(
            FhirTerminologyValidation terminologyValidation, Map<String, BillingProfileConfig> profiles) {
        this.terminologyValidation = terminologyValidation;
        this.profiles = profiles;
    }

    /**
     * Validates all coded entries in a {@link Composition} against the named billing profile.
     *
     * @param composition the composition whose coded entries should be validated
     * @param profileName the billing profile name (e.g. "us-claims")
     * @return {@link Try#success} if all billing codes are valid,
     *         {@link Try#failure} with {@link ConstraintViolationException} listing each failing code
     */
    public Try<Boolean, ConstraintViolationException> validateComposition(Composition composition, String profileName) {
        BillingProfileConfig profile = resolveProfile(profileName);
        if (profile == null) {
            return Try.failure(new ConstraintViolationException(
                    List.of(new ConstraintViolation("Unknown billing profile: " + profileName))));
        }
        if (!profile.enabled()) {
            LOG.debug("Billing profile '{}' is disabled, skipping validation", profileName);
            return Try.success(Boolean.TRUE);
        }

        List<CodePhrase> codedEntries = extractCodedEntries(composition);
        return doValidate(codedEntries, profile);
    }

    /**
     * Validates a list of coded entries against the named billing profile.
     *
     * @param codedEntries the coded entries to validate
     * @param profileName  the billing profile name
     * @return {@link Try#success} if all billing codes are valid,
     *         {@link Try#failure} with {@link ConstraintViolationException} listing each failing code
     */
    public Try<Boolean, ConstraintViolationException> validateCodedEntries(
            List<CodePhrase> codedEntries, String profileName) {
        BillingProfileConfig profile = resolveProfile(profileName);
        if (profile == null) {
            return Try.failure(new ConstraintViolationException(
                    List.of(new ConstraintViolation("Unknown billing profile: " + profileName))));
        }
        if (!profile.enabled()) {
            return Try.success(Boolean.TRUE);
        }
        return doValidate(codedEntries, profile);
    }

    private BillingProfileConfig resolveProfile(String profileName) {
        return profiles.get(profileName);
    }

    private Try<Boolean, ConstraintViolationException> doValidate(
            List<CodePhrase> codedEntries, BillingProfileConfig profile) {
        Set<String> configuredSystems = Set.copyOf(profile.codeSystems());
        List<ConstraintViolation> violations = new ArrayList<>();

        for (CodePhrase codePhrase : codedEntries) {
            String system = codePhrase.getTerminologyId().getValue();
            if (!configuredSystems.contains(system)) {
                continue;
            }

            Map<String, Try<Boolean, ConstraintViolationException>> results =
                    terminologyValidation.batchValidateCodes(Map.of(system, codePhrase));
            Try<Boolean, ConstraintViolationException> result = results.get(system);

            if (result != null && result.isFailure()) {
                ConstraintViolationException ex = result.getAsFailure().get();
                for (ConstraintViolation cv : ex.getConstraintViolations()) {
                    violations.add(new ConstraintViolation("Billing validation failed [system=%s, code=%s]: %s"
                            .formatted(system, codePhrase.getCodeString(), cv.getMessage())));
                }
            }
        }

        if (violations.isEmpty()) {
            return Try.success(Boolean.TRUE);
        }
        return Try.failure(new ConstraintViolationException(violations));
    }

    /**
     * Extracts all {@link CodePhrase} values from a {@link Composition}'s tree of content items.
     */
    static List<CodePhrase> extractCodedEntries(Composition composition) {
        List<CodePhrase> result = new ArrayList<>();
        if (composition == null || composition.getContent() == null) {
            return result;
        }
        for (ContentItem item : composition.getContent()) {
            collectFromContentItem(item, result);
        }
        return result;
    }

    private static void collectFromContentItem(ContentItem item, List<CodePhrase> result) {
        if (item instanceof Section section) {
            if (section.getItems() != null) {
                for (ContentItem child : section.getItems()) {
                    collectFromContentItem(child, result);
                }
            }
        } else if (item instanceof Observation observation) {
            collectFromHistory(observation.getData(), result);
            collectFromHistory(observation.getState(), result);
            collectFromCareEntryProtocol(observation, result);
        } else if (item instanceof Evaluation evaluation) {
            collectFromItemStructure(evaluation.getData(), result);
            collectFromCareEntryProtocol(evaluation, result);
        } else if (item instanceof Action action) {
            collectFromItemStructure(action.getDescription(), result);
            collectFromCareEntryProtocol(action, result);
        } else if (item instanceof Instruction instruction) {
            if (instruction.getActivities() != null) {
                for (Activity activity : instruction.getActivities()) {
                    collectFromItemStructure(activity.getDescription(), result);
                }
            }
            collectFromCareEntryProtocol(instruction, result);
        } else if (item instanceof AdminEntry adminEntry) {
            collectFromItemStructure(adminEntry.getData(), result);
        }
    }

    private static void collectFromCareEntryProtocol(
            com.nedap.archie.rm.composition.CareEntry careEntry, List<CodePhrase> result) {
        collectFromItemStructure(careEntry.getProtocol(), result);
    }

    @SuppressWarnings("unchecked")
    private static void collectFromHistory(History<?> history, List<CodePhrase> result) {
        if (history == null) {
            return;
        }
        if (history.getEvents() != null) {
            for (Event<?> event : (List<Event<?>>) (List<?>) history.getEvents()) {
                Object data = event.getData();
                if (data instanceof ItemStructure is) {
                    collectFromItemStructure(is, result);
                }
                Object state = event.getState();
                if (state instanceof ItemStructure is) {
                    collectFromItemStructure(is, result);
                }
            }
        }
        Object summary = history.getSummary();
        if (summary instanceof ItemStructure is) {
            collectFromItemStructure(is, result);
        }
    }

    private static void collectFromItemStructure(ItemStructure structure, List<CodePhrase> result) {
        if (structure == null) {
            return;
        }
        if (structure instanceof ItemTree tree) {
            collectFromItems(tree.getItems(), result);
        } else if (structure instanceof ItemList list) {
            collectFromElements(list.getItems(), result);
        } else if (structure instanceof ItemSingle single) {
            collectFromElement(single.getItem(), result);
        } else if (structure instanceof ItemTable table) {
            if (table.getRows() != null) {
                for (Cluster row : table.getRows()) {
                    collectFromItems(row.getItems(), result);
                }
            }
        }
    }

    private static void collectFromItems(List<? extends Item> items, List<CodePhrase> result) {
        if (items == null) {
            return;
        }
        for (Item item : items) {
            if (item instanceof Element element) {
                collectFromElement(element, result);
            } else if (item instanceof Cluster cluster) {
                collectFromItems(cluster.getItems(), result);
            }
        }
    }

    private static void collectFromElements(List<? extends Element> elements, List<CodePhrase> result) {
        if (elements == null) {
            return;
        }
        for (Element element : elements) {
            collectFromElement(element, result);
        }
    }

    private static void collectFromElement(Element element, List<CodePhrase> result) {
        if (element == null) {
            return;
        }
        if (element.getValue() instanceof DvCodedText dvCoded) {
            CodePhrase defining = dvCoded.getDefiningCode();
            if (defining != null && defining.getTerminologyId() != null) {
                result.add(defining);
            }
        }
    }

    /**
     * Configuration record for a billing profile, mirroring
     * {@code ExternalValidationProperties.BillingProfile}.
     */
    public record BillingProfileConfig(
            String name, String description, List<String> codeSystems, boolean enabled, String terminologyServerUrl) {}
}
