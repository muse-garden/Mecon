import React, { useEffect, useMemo, useRef, useState } from "react";
import { FrozenScore } from "@mecon/frozen-score/react";
import {
  ArrowLeft,
  ArrowRight,
  ArrowRightToLine,
  ChevronRight,
  Plus,
  Trash2,
} from "lucide-react";

function ChordConstructionScore({ construction }) {
  if (!construction.bundle) return null;
  const mutedTints = Object.fromEntries(
    (construction.mutedElementIds ?? []).map((id) => [id, "#a6abb3"]),
  );
  return <figure className="chord-construction-score">
    <FrozenScore bundle={construction.bundle} renderer="svg"
      className="chord-construction-frozen" aria-label={construction.caption}
      background="#f5f6f8" elementTints={mutedTints}
      hiddenIds={construction.hiddenElementIds ?? []} />
    <figcaption>{construction.caption}</figcaption>
  </figure>;
}

function DetailSections({ sections = [] }) {
  return sections.map((section, index) => <section className="chord-detail-section"
    data-severity={section.severity} key={`${section.title}:${index}`}>
    {section.title && <h4>{section.title}</h4>}
    {section.lines.map((line, lineIndex) => <p key={lineIndex}>{line}</p>)}
  </section>);
}

function ChordDetailView({ detail, emptyMessage }) {
  if (!detail) return <p>{emptyMessage}</p>;
  return <div className="chord-detail-view">
    <header>
      <h3>{detail.title}</h3>
      {detail.subtitle && <p>{detail.subtitle}</p>}
      {!!detail.badges.length && <small>{detail.badges.join(" · ")}</small>}
    </header>
    {detail.missingKnowledgeMessage && <p className="chord-detail-missing">{detail.missingKnowledgeMessage}</p>}
    <DetailSections sections={detail.commonSections} />
    {detail.explanations.map((explanation) => <section className="chord-explanation" key={explanation.id}>
      {(detail.explanations.length > 1 || explanation.title !== detail.title) && <h3>{explanation.title}</h3>}
      {explanation.subtitle && <p>{explanation.subtitle}</p>}
      {!!explanation.badges.length && <small className="chord-detail-badges">{explanation.badges.join(" · ")}</small>}
      <DetailSections sections={explanation.commonSections} />
      {explanation.routes.map((route) => <article className="chord-detail-route" key={route.id}>
        <header>
          <h4>{route.title}</h4>
          {route.badge && <span>{route.badge}</span>}
          {route.subtitle && <small>{route.subtitle}</small>}
        </header>
        <DetailSections sections={route.sections} />
        {route.construction && <>
          {route.construction.showDescription && <p>{route.construction.description}</p>}
          <ChordConstructionScore construction={route.construction} />
        </>}
        {!!route.sources.length && <p className="chord-detail-sources">
          {route.sources.map((source) => `${source.label}　${source.detail}`).join("；")}
        </p>}
      </article>)}
      {!!explanation.sources.length && <p className="chord-detail-sources">
        {explanation.sources.map((source) => `${source.label}　${source.detail}`).join("；")}
      </p>}
    </section>)}
  </div>;
}
import { CircleOfFifthsPicker } from "@mecon/web-renderer/react";

function PlanIcon({ icon: Icon, size = 17 }) {
  return <Icon aria-hidden="true" size={size} strokeWidth={1.8} />;
}

function ChoiceList({ items, toneMode, disabled = false, onChoose }) {
  return <div className="practice-choice-list">
    {items.map((item) => <button key={item.id} type="button" aria-pressed={item.selected}
      disabled={disabled} onClick={() => onChoose(item)}>
      {item.displayLabel || (toneMode === "ABSOLUTE" ? item.absoluteTonesLabel : item.relativeTonesLabel)}
    </button>)}
  </div>;
}

export function PracticePlanPanel({
  update,
  catalogChoiceId,
  onCatalogChoiceChange,
  onReplaceChord,
  onSelectSlot,
  onAppendChord,
  onRemoveChord,
  onSetBass,
  onSetTonality,
  onSetPivot,
  onSetTonalKey,
  onInsertTonalLayout,
  onRemoveTonalLayout,
  onSelectTonalLayout,
  onInsertIdiom,
  onReplaceIdiom,
  onRemoveIdiom,
  onSelectIdiom,
  onSetCatalogFilter,
  sections = ["tonality", "harmony", "details", "idioms"],
  chordDetailsInitiallyOpen = false,
}) {
  const plan = update?.plan;
  const strings = plan?.strings;
  const [terminatePrevious, setTerminatePrevious] = useState(true);
  const [showInsertTonalLayout, setShowInsertTonalLayout] = useState(false);
  const insertTonalLayoutRef = useRef(null);
  const [toneMode, setToneMode] = useState("RELATIVE");
  const [showDoubleTonality, setShowDoubleTonality] = useState(false);
  const [idiomTab, setIdiomTab] = useState("RELATED");
  const [targetKeyId, setTargetKeyId] = useState("all");

  useEffect(() => {
    if (!showInsertTonalLayout) return undefined;

    const dismissOnOutsidePointer = (event) => {
      if (!insertTonalLayoutRef.current?.contains(event.target)) {
        setShowInsertTonalLayout(false);
      }
    };
    const dismissOnEscape = (event) => {
      if (event.key === "Escape") setShowInsertTonalLayout(false);
    };

    document.addEventListener("pointerdown", dismissOnOutsidePointer);
    document.addEventListener("keydown", dismissOnEscape);
    return () => {
      document.removeEventListener("pointerdown", dismissOnOutsidePointer);
      document.removeEventListener("keydown", dismissOnEscape);
    };
  }, [showInsertTonalLayout]);

  useEffect(() => {
    if (!plan?.idiomCatalog?.includeOffKey) setTargetKeyId("all");
  }, [plan?.idiomCatalog?.includeOffKey]);

  const idiomDefinitions = useMemo(() => {
    const definitions = plan?.idiomCatalog?.definitions ?? [];
    return definitions.map((definition) => ({
      ...definition,
      variants: definition.variants.filter((variant) => {
        const inTab = idiomTab === "RELATED" ? variant.relatedToFocus : variant.availableByDefault;
        if (!inTab) return false;
        if (targetKeyId === "all") return true;
        return variant.suggestedKey &&
          `${variant.suggestedKey.fifths}:${variant.suggestedKey.mode}` === targetKeyId;
      }),
    })).filter((definition) => definition.variants.length > 0);
  }, [plan?.idiomCatalog?.generation, idiomTab, targetKeyId]);

  if (!update || !plan || !strings) return <aside className="panel practice-plan-panel">
    <h2>{strings?.unloadedTitle ?? "计划"}</h2>
    <p>{strings?.unloadedMessage ?? ""}</p>
  </aside>;

  const selectedIdiom = plan.coveredIdioms.find(
    (idiom) => idiom.id === update.selection?.idiomInstanceId,
  );
  const tonalityChoices = plan.tonalityChoices;
  const readingTones = (reading) => toneMode === "ABSOLUTE"
    ? reading.absoluteTonesLabel : reading.relativeTonesLabel;
  const writingLocked = update.writing.phase === "RUNNING";
  const visibleSections = new Set(sections);

  return <aside className="panel practice-plan-panel" aria-label={strings.panelAriaLabel}>
    {visibleSections.has("tonality") && <details className="plan-section workbench-panel" open>
      <summary><ChevronRight className="disclosure-icon" aria-hidden="true" size={17} strokeWidth={1.8} /><h2>{strings.currentTonalityTitle}</h2></summary>
    <div className="tonal-layout-actions">
      <div className="tonal-layout-list">
        {plan.activeTonalLayouts.map((layout) => <div key={layout.id}
          className={layout.id === plan.editableTonalLayoutId ? "tonal-layout-row selected" : "tonal-layout-row"}>
          <button type="button" className="tonal-layout-control"
            aria-pressed={layout.id === plan.editableTonalLayoutId}
            onClick={() => onSelectTonalLayout(layout.id)}>
            <strong>{layout.keyLabel}</strong><span>{layout.rangeLabel}</span>
            {layout.baselineLabel && <small>{layout.baselineLabel}</small>}
          </button>
          {!layout.isBaseline && <button type="button" aria-label={strings.deleteTonalLayout}
            onClick={() => onRemoveTonalLayout(layout.id)}>{strings.deleteTonalLayout}</button>}
        </div>)}
      </div>
      <div className="plan-create-layout" ref={insertTonalLayoutRef}>
        <button type="button" className="tonal-layout-insert-button"
          aria-haspopup="dialog" aria-expanded={showInsertTonalLayout}
          onClick={() => setShowInsertTonalLayout((open) => !open)}>
          + {strings.insert}
        </button>
        {showInsertTonalLayout && <div className="tonal-layout-insert-popup"
          role="dialog" aria-label={strings.insertTonalLayoutTitle}>
          <strong>{strings.insertTonalLayoutTitle}</strong>
          <label className="tonal-layout-terminate-row">
            <span>{strings.terminatePreviousLayout}</span>
            <input type="checkbox" checked={terminatePrevious}
              onChange={(event) => setTerminatePrevious(event.target.checked)} />
          </label>
          <CircleOfFifthsPicker options={plan.tonalKeyChoices} currentKey={plan.currentKey}
            onKeyClick={(key) => {
              onInsertTonalLayout(key, terminatePrevious);
              setShowInsertTonalLayout(false);
            }} />
        </div>}
      </div>
    </div>
      <p className="panel-help">{strings.tonalLayoutHelp}</p>
    </details>}

    {visibleSections.has("harmony") && <details className="plan-section workbench-panel harmony-panel" open>
      <summary><ChevronRight className="disclosure-icon" aria-hidden="true" size={17} strokeWidth={1.8} /><h2>{strings.harmonySelectionTitle}</h2></summary>
      <div className="harmony-panel-body">
        <div className="harmony-panel-controls">
      <div className="selected-chord-header">
        <div className="selected-chord-summary">
          {!plan.selectedChordReadings.length &&
            <strong>{plan.selectedChord?.symbol ?? plan.selectedSlot?.symbol ?? strings.selectedChordEmpty}</strong>}
          {plan.selectedChordReadings.map((reading) => <div key={`${reading.fifths}:${reading.mode}:${reading.functionalSymbol}`}>
            <strong>{reading.symbolLabel}</strong><small>{readingTones(reading)}</small>
          </div>)}
        </div>
        <div className="slot-navigation" role="group">
        <button aria-label={plan.navigation.previousLabel} disabled={!plan.navigation.previousSlotId}
          title={plan.navigation.previousLabel}
          onClick={() => onSelectSlot(plan.navigation.previousSlotId)}><PlanIcon icon={ArrowLeft} /></button>
        <button aria-label={plan.navigation.nextLabel} disabled={!plan.navigation.nextSlotId}
          title={plan.navigation.nextLabel}
          onClick={() => onSelectSlot(plan.navigation.nextSlotId)}><PlanIcon icon={ArrowRight} /></button>
        {plan.navigation.nextSlotId ? <button aria-label={plan.navigation.lastLabel}
          title={plan.navigation.lastLabel}
          onClick={() => onSelectSlot(plan.navigation.lastSlotId)}><PlanIcon icon={ArrowRightToLine} /></button>
          : <button aria-label={plan.navigation.appendLabel} disabled={!plan.navigation.appendOnset}
            title={plan.navigation.appendLabel}
            onClick={() => onAppendChord(plan.navigation.appendOnset)}><PlanIcon icon={Plus} /></button>}
        {plan.selectedSlot?.capabilities?.canRemove && <button aria-label={plan.navigation.removeChord}
          title={plan.navigation.removeChord} onClick={onRemoveChord}><PlanIcon icon={Trash2} /></button>}
        </div>
      </div>

      {!!plan.coveredIdiomRows.length && <div className="practice-flat-list">
        <small>{strings.coveredIdioms}</small>
        <ul className="practice-idiom-list compact">{plan.coveredIdiomRows.map((row) =>
          <li key={row.id} className="practice-flat-row">
          <button type="button" aria-pressed={row.id === selectedIdiom?.id}
            onClick={() => onSelectIdiom(row.id)}>{row.displayLabel}</button>
          {row.startsHere && <button aria-label={strings.removeIdiom}
            onClick={() => onRemoveIdiom(row.id)}>{strings.detachIdiom}</button>}
          </li>)}</ul>
      </div>}

      {(plan.continuationTonalityChoices.length ||
        plan.doubleTonalityChoices.length || tonalityChoices.length) > 0 && <div className="practice-off-key">
        <strong>{strings.offKey}</strong>
        {!!plan.continuationTonalityChoices.length && <>
          <span>{strings.continueTemporaryTonality}</span>
          <ChoiceList items={plan.continuationTonalityChoices} toneMode={toneMode}
            disabled={plan.chordLocked} onChoose={(item) => onSetTonality(item.tonality)} />
          <small>{strings.temporaryTonalityHelp}</small>
        </>}
        {!plan.chordLocked && !!plan.doubleTonalityChoices.length && <button type="button"
          aria-pressed={showDoubleTonality} onClick={() => setShowDoubleTonality((value) => !value)}>
          {showDoubleTonality ? strings.collapseDoubleTonality : strings.createDoubleTonality}
        </button>}
        {showDoubleTonality && <div className="practice-flat-list">
          {plan.doubleTonalityChoices.map((item) => <button key={item.id} type="button"
            onClick={() => { onSetTonality(item.tonality); setShowDoubleTonality(false); }}>
            <strong>{item.keyLabel}</strong>
            <span>{toneMode === "ABSOLUTE" ? item.absoluteTonesLabel : item.relativeTonesLabel}</span>
            <small>{item.directionLabel}</small>
          </button>)}
        </div>}
        {plan.chordLocked && <small>{strings.lockedTonalityHelp}</small>}
      </div>}

      <div className="bass-and-tone-controls">
        <div className="bass-selection">
          <span>{strings.bass}</span>
          <div className="practice-choice-list idiom-target-filter" role="group" aria-label={strings.bass}>
            {plan.bassChoices.map((choice) => <button key={choice.pitchClass ?? "any"} type="button"
              aria-pressed={choice.selected} disabled={plan.inversionLocked}
              onClick={() => onSetBass(choice.pitchClass)}>
              {toneMode === "ABSOLUTE" ? choice.absoluteLabel : choice.relativeLabel}
            </button>)}
          </div>
        </div>
        <div className="tone-mode" role="group">
          <button type="button" aria-pressed={toneMode === "RELATIVE"}
            onClick={() => setToneMode("RELATIVE")}>{strings.relativePitch}</button>
          <button type="button" aria-pressed={toneMode === "ABSOLUTE"}
            onClick={() => setToneMode("ABSOLUTE")}>{strings.absolutePitch}</button>
        </div>
      </div>
      <label className="check-row"><input type="checkbox" checked={plan.pivotEnabled}
        disabled={!plan.selectedSlotId || plan.chordLocked}
        onChange={(event) => onSetPivot(event.target.checked)} />{strings.pivotChord}</label>
        </div>
      <div className="chord-catalog-groups" aria-label={strings.chordCatalog}>
        {plan.chordCatalogGroups.map((group) => <section key={group.id} className="chord-catalog-group">
          <p><strong>{group.titleLabel}：</strong>{group.descriptionLabel}</p>
          <div className="practice-choice-list">{group.choices.map((choice) => <button
            key={choice.id} type="button" aria-pressed={choice.id === catalogChoiceId}
            disabled={plan.chordLocked} onClick={() => {
              onCatalogChoiceChange(choice.id);
              onReplaceChord(choice.id);
            }}>{toneMode === "ABSOLUTE" ? choice.absoluteLabel : choice.relativeLabel}</button>)}</div>
        </section>)}
      </div>
      </div>
    </details>}

    {visibleSections.has("details") && <details className="plan-section workbench-panel chord-details"
      open={chordDetailsInitiallyOpen || undefined}>
      <summary><ChevronRight className="disclosure-icon" aria-hidden="true" size={17} strokeWidth={1.8} /><h2>{strings.chordDetailTitle}</h2></summary>
      <ChordDetailView detail={plan.chordDetail} emptyMessage={strings.noChordDetail} />
    </details>}

    {visibleSections.has("idioms") && <details className="plan-section workbench-panel idiom-panel" open>
      <summary><ChevronRight className="disclosure-icon" aria-hidden="true" size={17} strokeWidth={1.8} /><h2>{strings.idiomTitle}</h2></summary>
      <label className="check-row"><input type="checkbox" checked={plan.idiomCatalog.includeOffKey}
        onChange={(event) => onSetCatalogFilter(event.target.checked)} />{strings.showOffKeyIdioms}</label>
      {plan.idiomCatalog.includeOffKey && !!plan.idiomTargetKeys.length && <>
      <span>{strings.filterTargetKey}</span>
      <div className="practice-choice-list idiom-tabs">
        <button type="button" aria-pressed={targetKeyId === "all"}
          onClick={() => setTargetKeyId("all")}>{strings.allTargetKeys}</button>
        {plan.idiomTargetKeys.map((item) => <button key={item.id} type="button"
          aria-pressed={targetKeyId === item.id} onClick={() => setTargetKeyId(item.id)}>{item.label}</button>)}
      </div>
      </>}
      <div className="practice-choice-list">
        <button type="button" aria-pressed={idiomTab === "RELATED"}
          onClick={() => setIdiomTab("RELATED")}>{strings.relatedIdioms}</button>
        <button type="button" aria-pressed={idiomTab === "ALL"}
          onClick={() => setIdiomTab("ALL")}>{strings.allIdioms}</button>
      </div>
      {plan.idiomCatalog.loading && <p aria-live="polite">
        {idiomTab === "RELATED" ? strings.loadingRelatedIdioms : strings.loadingAllIdioms}</p>}
      {plan.idiomCatalog.errorKey && <p role="alert">{strings.idiomCatalogError}：{plan.idiomCatalog.errorKey}</p>}
      {!plan.idiomCatalog.loading && !plan.idiomCatalog.errorKey && !idiomDefinitions.length && <p>
        {idiomTab === "RELATED" ? strings.noRelatedIdioms : strings.noAllIdioms}</p>}
      <div className="practice-idiom-catalog" data-testid="idiom-catalog"
        data-generation={plan.idiomCatalog.generation}>
        {idiomDefinitions.map((definition) => <div key={definition.id} className="practice-flat-list">
          <strong>{definition.title}</strong>
          <div className="practice-choice-list">{definition.variants.filter((variant) =>
            idiomTab === "RELATED" ? variant.relatedToFocus : variant.availableByDefault,
          ).filter((variant) => targetKeyId === "all" || (variant.suggestedKey &&
            `${variant.suggestedKey.fifths}:${variant.suggestedKey.mode}` === targetKeyId)).map((variant) => <button
            key={variant.id} type="button" disabled={!variant.enabled || writingLocked}
            title={variant.disabledReasonLabel ?? undefined}
            aria-pressed={selectedIdiom?.definitionId === definition.id && selectedIdiom?.variantId === variant.id}
            onClick={() => selectedIdiom
              ? onReplaceIdiom(selectedIdiom.id, definition.id, variant.id)
              : onInsertIdiom(definition.id, variant.id)}>{variant.displayLabel}</button>)}</div>
        </div>)}
      </div>
    </details>}
  </aside>;
}
