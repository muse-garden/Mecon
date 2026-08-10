# Free-practice note constraints

Free-practice schema v9 persists note roles and writing locks in
`FreePracticeDocument.noteConstraints`. The data is source state, not a rendered annotation or a
snapshot of the current score.

## Stable notehead identity

`PracticeNoteheadRef(eventId, pitchIndex)` identifies one notehead in a notation event. An explicit
role or notehead lock becomes stale when that event or pitch index disappears; readers ignore stale
references and a later editing operation may prune them.

An explicit `CHORD_TONE` / `NON_CHORD_TONE` role applies at the note's onset. Sustained-note role
changes inside the note are intentionally not represented in v9. A future edit operation may split
such a note into tied events before assigning different roles.

## Dynamic lock rules

`PracticeNoteConstraintState` stores three independent lock sets:

- `lockedNoteheads`: exact noteheads;
- `lockedVoiceTrackIds`: every current and future note on a notation voice track;
- `lockedStaffTrackIds`: every current and future note on every voice belonging to the staff.

Voice and staff locks are predicates evaluated against the current score. They are never expanded
into notehead snapshots. This preserves the workflow where a user locks a melody voice and continues
composing it while accompaniment writing remains unable to replace it.

Locks constrain automatic writing only. Normal score-edit intents remain available so the user can
edit locked material directly. Undo/redo captures the constraint state in the same free-practice
history transaction as related document or score changes.

## Derived views

The shared free-practice session derives notehead presentation and catalog constraints. Desktop and
Web render the typed view and dispatch intents; they do not infer chord membership themselves.

For a selected chord, membership uses the selected chord's audible pitch classes at each note onset.
An explicit role that disagrees with membership is exposed as a conflict. Optional catalog filters
apply explicit roles only: chord tones require membership and non-chord tones forbid membership.
The session repeats this validation when a choice or idiom is committed, so UI filtering is not a
business-rule boundary.

