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

import com.nedap.archie.rm.archetyped.Locatable;
import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.Activity;
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
import com.nedap.archie.rm.datastructures.Event;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemList;
import com.nedap.archie.rm.datastructures.ItemSingle;
import com.nedap.archie.rm.datastructures.ItemTable;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ehrbase.openehr.sdk.util.functional.Try;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates billing-relevant coded entries in a Composition against configured
 * billing code systems (ICD-10, CPT/HCPCS, etc.) using the existing FHIR
 * terminology adapter.
 */
public class BillingCodeValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BillingCodeValidator.class);

    private final Set<String> diagnosisCodeSystems;
    private final Set<String> procedureCodeSystems;
    private final Set<String> supportingCodeSystems;
    private final Set<String> allBillingCodeSystems;
    private final ExternalTerminologyValidation terminologyValidation;

    public BillingCodeValidator(
            Set<String> diagnosisCodeSystems,
            Set<String> procedureCodeSystems,
            Set<String> supportingCodeSystems,
            ExternalTerminologyValidation terminologyValidation) {
        this.diagnosisCodeSystems = Set.copyOf(diagnosisCodeSystems);
        this.procedureCodeSystems = Set.copyOf(procedureCodeSystems);
        this.supportingCodeSystems = Set.copyOf(supportingCodeSystems);

        Set<String> all = new HashSet<>();
        all.addAll(this.diagnosisCodeSystems);
        all.addAll(this.procedureCodeSystems);
        all.addAll(this.supportingCodeSystems);
        this.allBillingCodeSystems = Set.copyOf(all);

        this.terminologyValidation = terminologyValidation;
    }

    /**
     * Validates billing-relevant coded entries in the given Composition.
     *
     * @return list of constraint violations for invalid billing codes; empty if all valid
     */
    public List<ConstraintViolation> validate(Composition composition) {
        List<CodeEntry> collectedEntries = new ArrayList<>();
        collectBillingCodes(composition, collectedEntries);

        Set<SystemCodePair> validated = new HashSet<>();
        List<ConstraintViolation> violations = new ArrayList<>();

        for (CodeEntry entry : collectedEntries) {
            SystemCodePair pair = new SystemCodePair(entry.system(), entry.code());
            if (!validated.add(pair)) {
                continue;
            }

            Try<Boolean, ConstraintViolationException> result = validateCodeAgainstTerminology(entry);
            if (result.isFailure()) {
                ConstraintViolationException ex = result.getAsFailure().get();
                for (ConstraintViolation cv : ex.getConstraintViolations()) {
                    String category = classifyCodeSystem(entry.system());
                    violations.add(
                            new ConstraintViolation("Billing validation failed [%s] for code system '%s', code '%s': %s"
                                    .formatted(category, entry.system(), entry.code(), cv.getMessage())));
                }
            }
        }

        return violations;
    }

    private Try<Boolean, ConstraintViolationException> validateCodeAgainstTerminology(CodeEntry entry) {
        try {
            CodePhrase codePhrase = new CodePhrase(new TerminologyId(entry.system()), entry.code());
            String serviceApi = "//fhir.hl7.org/CodeSystem/$validate-code?url=" + entry.system();
            TerminologyParam param = TerminologyParam.ofFhir(serviceApi);
            param.setCodePhrase(codePhrase);
            param.useCodeSystem();

            if (!terminologyValidation.supports(param)) {
                LOG.debug("Terminology server does not support code system: {}", entry.system());
                return Try.success(Boolean.TRUE);
            }

            return terminologyValidation.validate(param);
        } catch (Exception e) {
            LOG.warn(
                    "Error during billing code validation for system={}, code={}: {}",
                    entry.system(),
                    entry.code(),
                    e.getMessage());
            return Try.success(Boolean.FALSE);
        }
    }

    private String classifyCodeSystem(String system) {
        if (diagnosisCodeSystems.contains(system)) {
            return "diagnosis";
        } else if (procedureCodeSystems.contains(system)) {
            return "procedure";
        } else if (supportingCodeSystems.contains(system)) {
            return "supporting";
        }
        return "unknown";
    }

    private void collectBillingCodes(Composition composition, List<CodeEntry> entries) {
        if (composition.getContent() == null) {
            return;
        }
        for (ContentItem content : composition.getContent()) {
            traverseLocatable(content, entries);
        }
    }

    private void traverseLocatable(Locatable locatable, List<CodeEntry> entries) {
        if (locatable == null) {
            return;
        }

        if (locatable instanceof Section section) {
            if (section.getItems() != null) {
                for (ContentItem item : section.getItems()) {
                    traverseLocatable(item, entries);
                }
            }
        } else if (locatable instanceof Entry entry) {
            traverseEntry(entry, entries);
        }
    }

    private void traverseEntry(Entry entry, List<CodeEntry> entries) {
        if (entry instanceof Observation obs) {
            traverseStructure(obs.getData(), entries);
            traverseStructure(obs.getState(), entries);
            traverseStructure(obs.getProtocol(), entries);
        } else if (entry instanceof Evaluation eval) {
            traverseStructure(eval.getData(), entries);
            traverseStructure(eval.getProtocol(), entries);
        } else if (entry instanceof Instruction instr) {
            traverseStructure(instr.getProtocol(), entries);
            if (instr.getActivities() != null) {
                for (Activity activity : instr.getActivities()) {
                    traverseStructure(activity.getDescription(), entries);
                }
            }
        } else if (entry instanceof Action action) {
            traverseStructure(action.getDescription(), entries);
            traverseStructure(action.getProtocol(), entries);
        } else if (entry instanceof AdminEntry admin) {
            traverseStructure(admin.getData(), entries);
        }
    }

    private void traverseStructure(Object structure, List<CodeEntry> entries) {
        if (structure == null) {
            return;
        }

        if (structure instanceof History<?> history) {
            if (history.getEvents() != null) {
                for (Object event : history.getEvents()) {
                    if (event instanceof Event<?> ev) {
                        traverseStructure(ev.getData(), entries);
                        traverseStructure(ev.getState(), entries);
                    }
                }
            }
            traverseStructure(history.getSummary(), entries);
        } else if (structure instanceof ItemTree tree) {
            traverseItems(tree.getItems(), entries);
        } else if (structure instanceof ItemList list) {
            traverseItems(list.getItems(), entries);
        } else if (structure instanceof ItemTable table) {
            traverseItems(table.getRows(), entries);
        } else if (structure instanceof ItemSingle single) {
            traverseElement(single.getItem(), entries);
        }
    }

    private void traverseItems(List<? extends Item> items, List<CodeEntry> entries) {
        if (items == null) {
            return;
        }
        for (Item item : items) {
            if (item instanceof Element element) {
                traverseElement(element, entries);
            } else if (item instanceof Cluster cluster) {
                traverseItems(cluster.getItems(), entries);
            }
        }
    }

    private void traverseElement(Element element, List<CodeEntry> entries) {
        if (element == null || element.getValue() == null) {
            return;
        }

        if (element.getValue() instanceof DvCodedText codedText) {
            extractBillingCode(codedText, entries);
        }
    }

    private void extractBillingCode(DvCodedText codedText, List<CodeEntry> entries) {
        CodePhrase definingCode = codedText.getDefiningCode();
        if (definingCode == null || definingCode.getTerminologyId() == null) {
            return;
        }

        String system = definingCode.getTerminologyId().getValue();
        String code = definingCode.getCodeString();

        if (system != null && code != null && allBillingCodeSystems.contains(system)) {
            entries.add(new CodeEntry(system, code));
        }
    }

    record CodeEntry(String system, String code) {}

    record SystemCodePair(String system, String code) {}
}
