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

import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Entry;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.EobMappingService;
import org.ehrbase.repository.CompositionRepository;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.DiagnosisComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ProcedureComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.TotalComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.Use;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link EobMappingService} implementation that transforms openEHR Compositions
 * into FHIR R4 ExplanationOfBenefit resources.
 */
@Service
public class EobMappingServiceImp implements EobMappingService {

    private static final Logger logger = LoggerFactory.getLogger(EobMappingServiceImp.class);

    private final CompositionService compositionService;
    private final CompositionRepository compositionRepository;
    private final EhrService ehrService;

    public EobMappingServiceImp(
            CompositionService compositionService, CompositionRepository compositionRepository, EhrService ehrService) {
        this.compositionService = Objects.requireNonNull(compositionService);
        this.compositionRepository = Objects.requireNonNull(compositionRepository);
        this.ehrService = Objects.requireNonNull(ehrService);
    }

    @Override
    public ExplanationOfBenefit mapToEob(UUID ehrId, Composition composition) {
        ExplanationOfBenefit eob = new ExplanationOfBenefit();

        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        eob.setUse(Use.CLAIM);

        if (composition.getUid() != null) {
            eob.setId(composition.getUid().getValue());
        }

        eob.setPatient(new Reference("Patient/" + ehrId));

        if (composition.getComposer() != null) {
            String composerName = composition.getComposer().toString();
            eob.setProvider(new Reference().setDisplay(composerName));
        }

        if (composition.getContext() != null && composition.getContext().getStartTime() != null) {
            eob.setCreated(convertDvDateTime(composition.getContext().getStartTime()));
        }

        eob.setType(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                        .setCode("professional")
                        .setDisplay("Professional")));

        List<DiagnosisComponent> diagnoses = new ArrayList<>();
        List<ProcedureComponent> procedures = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        if (composition.getContent() != null) {
            int diagSequence = 1;
            int procSequence = 1;
            for (ContentItem item : composition.getContent()) {
                diagSequence = extractDiagnoses(item, diagnoses, diagSequence);
                procSequence = extractProcedures(item, procedures, procSequence);
                totalAmount = totalAmount.add(extractCost(item));
            }
        }

        eob.setDiagnosis(diagnoses);
        eob.setProcedure(procedures);

        TotalComponent total = new TotalComponent();
        total.setCategory(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/adjudication")
                        .setCode("submitted")
                        .setDisplay("Submitted Amount")));
        Money money = new Money();
        money.setValue(totalAmount);
        money.setCurrency("USD");
        total.setAmount(money);
        eob.addTotal(total);

        ExplanationOfBenefit.InsuranceComponent insurance = new ExplanationOfBenefit.InsuranceComponent();
        insurance.setFocal(true);
        insurance.setCoverage(new Reference("Coverage/" + ehrId));
        eob.addInsurance(insurance);

        return eob;
    }

    @Override
    public EobResult getExplanationOfBenefits(UUID ehrId, int offset, int count) {
        ehrService.checkEhrExists(ehrId);

        List<UUID> compositionIds = compositionRepository.findCompositionIdsByEhr(ehrId);
        int totalCount = compositionIds.size();

        int fromIndex = Math.min(offset, totalCount);
        int toIndex = Math.min(fromIndex + count, totalCount);
        List<UUID> pagedIds = compositionIds.subList(fromIndex, toIndex);

        List<ExplanationOfBenefit> results = new ArrayList<>();
        for (UUID compositionId : pagedIds) {
            compositionService
                    .retrieve(ehrId, compositionId, null)
                    .map(comp -> mapToEob(ehrId, comp))
                    .ifPresent(results::add);
        }

        return new EobResult(results, totalCount);
    }

    private Date convertDvDateTime(com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime dvDateTime) {
        TemporalAccessor temporal = dvDateTime.getValue();
        Instant instant;
        if (temporal instanceof OffsetDateTime odt) {
            instant = odt.toInstant();
        } else if (temporal instanceof LocalDateTime ldt) {
            instant = ldt.toInstant(ZoneOffset.UTC);
        } else if (temporal instanceof Instant inst) {
            instant = inst;
        } else {
            logger.warn(
                    "Unhandled temporal type {} in DvDateTime, skipping created date",
                    temporal.getClass().getName());
            return null;
        }
        return Date.from(instant);
    }

    private int extractDiagnoses(ContentItem item, List<DiagnosisComponent> diagnoses, int sequence) {
        if (item instanceof Evaluation evaluation) {
            Optional<CodeableConcept> code = extractCodeFromEntry(evaluation);
            if (code.isPresent()) {
                DiagnosisComponent diag = new DiagnosisComponent();
                diag.setSequence(sequence++);
                diag.setDiagnosis(code.get());
                diagnoses.add(diag);
            }
        } else if (item instanceof Section section && section.getItems() != null) {
            for (ContentItem child : section.getItems()) {
                sequence = extractDiagnoses(child, diagnoses, sequence);
            }
        }
        return sequence;
    }

    private int extractProcedures(ContentItem item, List<ProcedureComponent> procedures, int sequence) {
        if (item instanceof Action action) {
            Optional<CodeableConcept> code = extractCodeFromEntry(action);
            if (code.isPresent()) {
                ProcedureComponent proc = new ProcedureComponent();
                proc.setSequence(sequence++);
                proc.setProcedure(code.get());
                procedures.add(proc);
            }
        } else if (item instanceof Section section && section.getItems() != null) {
            for (ContentItem child : section.getItems()) {
                sequence = extractProcedures(child, procedures, sequence);
            }
        }
        return sequence;
    }

    private Optional<CodeableConcept> extractCodeFromEntry(Entry entry) {
        DvText name = entry.getName();
        if (name instanceof DvCodedText codedText && codedText.getDefiningCode() != null) {
            CodeableConcept concept = new CodeableConcept();
            concept.addCoding(new Coding()
                    .setSystem(
                            codedText.getDefiningCode().getTerminologyId() != null
                                    ? codedText
                                            .getDefiningCode()
                                            .getTerminologyId()
                                            .getValue()
                                    : "http://unknown")
                    .setCode(codedText.getDefiningCode().getCodeString())
                    .setDisplay(codedText.getValue()));
            return Optional.of(concept);
        } else if (name != null) {
            CodeableConcept concept = new CodeableConcept();
            concept.setText(name.getValue());
            return Optional.of(concept);
        }
        return Optional.empty();
    }

    private BigDecimal extractCost(ContentItem item) {
        BigDecimal cost = BigDecimal.ZERO;
        if (item instanceof Observation observation) {
            cost = cost.add(extractCostFromHistory(observation.getData()));
        } else if (item instanceof Evaluation evaluation) {
            cost = cost.add(extractCostFromItemStructure(evaluation.getData()));
        } else if (item instanceof Action action) {
            cost = cost.add(extractCostFromItemStructure(action.getDescription()));
        }
        if (item instanceof Section section && section.getItems() != null) {
            for (ContentItem child : section.getItems()) {
                cost = cost.add(extractCost(child));
            }
        }
        return cost;
    }

    private BigDecimal extractCostFromHistory(History<ItemStructure> history) {
        if (history == null || history.getEvents() == null) {
            return BigDecimal.ZERO;
        }
        return history.getEvents().stream()
                .filter(e -> e.getData() != null)
                .map(e -> extractCostFromItemStructure(e.getData()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal extractCostFromItemStructure(ItemStructure data) {
        if (data instanceof ItemTree tree && tree.getItems() != null) {
            return tree.getItems().stream()
                    .filter(Element.class::isInstance)
                    .map(Element.class::cast)
                    .filter(e -> e.getValue() instanceof DvQuantity)
                    .map(e -> ((DvQuantity) e.getValue()).getMagnitude())
                    .filter(Objects::nonNull)
                    .map(BigDecimal::valueOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return BigDecimal.ZERO;
    }
}
