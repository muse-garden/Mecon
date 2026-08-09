# Test Scores for Renderer Validation

This directory contains single-score fixtures (`.mscore.yaml`, YAML `StorageScore`) for validating
the music notation renderer. The `.mecon` extension now denotes the **zip container** format (multiple
scores + modules + frozen geometry) — see [../docs/data_model/mecon-container.md](../docs/data_model/mecon-container.md);
these fixtures were renamed from `.mecon` to keep the two apart.

## Test Coverage

| File | Description | Key Test Points |
|------|-------------|-----------------|
| `01_durations.mscore.yaml` | Various note durations | Whole, half, quarter, eighth, sixteenth notes without beaming |
| `02_beaming.mscore.yaml` | Beam group patterns | 2-note groups, 4-note groups, mixed durations, dotted rhythm beaming |
| `03_chords.mscore.yaml` | Chord rendering | Triads, sevenths, seconds, clusters, inversions, wide voicings |
| `04_rests.mscore.yaml` | Rest rendering | Whole, half, quarter, eighth rests; note-rest patterns |
| `05_polyphony.mscore.yaml` | Multi-voice on single staff | Two voices, stem direction, independent rhythms |
| `06_grand_staff.mscore.yaml` | Piano-style grand staff | Treble + bass clef, independent hands, Alberti bass |
| `07_ties.mscore.yaml` | Tie rendering | Cross-barline ties, tie chains, partial chord ties |
| `08_dotted_and_tuplets.mscore.yaml` | Special rhythms | Dotted notes (half, quarter), triplets (quarter, eighth) |
| `12_key_signatures.mscore.yaml` | Complex key signatures | Dramatic sharp↔flat jumps (M1-12); gradual 多/少升号 (M13-20) and 多/少降号 (M21-28); double sharps, double flats, naturals |
| `15_grace_notes.mscore.yaml` | Grace notes (basic) | Single grace (M1), two graces (M2), three graces (M3) — all PREVIOUS steal; single+two graces PRINCIPAL steal (M4–5) |
| `16_grace_ties_slurs.mscore.yaml` | Grace notes — ties & slurs | Grace→grace tie (M1), grace→principal tie (M2), chord grace multi-tie (M3), slur grace→principal (M4), slur grace→multi-note (M5) |
| `17_articulations.mscore.yaml` | Articulations | All 5 marks stem-up→below (M1) / stem-down→above (M2); stacked combos & Spiccato (M2); placement=STEM (M3); slur avoiding staccato dots (M4); chord articulations (M5) |
| `19_dynamics.mscore.yaml` | Dynamics & hairpins | p / mf (composite, ABOVE) / ff letter marks (M1, M3); crescendo & diminuendo WEDGE hairpins (M2, M3); cresc. text + dashed line (M4); controller track linkage; vertical spacing below the staff |

## Usage

1. Load each score file in the application
2. Visually verify the rendering matches expected notation
3. If rendering is correct, convert to unit test fixture

## File Format

Files use the `.mscore.yaml` extension (single-score YAML `StorageScore`). Key structure:

```yaml
id: "score-id"
metadata: { title, composer, ... }
defaultTimeSignature: { numerator, denominator }
defaultKeySignature: { root, mode }
measures: [ { number: 1 }, ... ]
pitchTracks: { track-id: { events: [...] } }
voiceTracks: { track-id: { events: [...], pitchTrackId: "..." } }
staffTracks: { track-id: { clef, voiceTrackIds: [...] } }
partTracks: { track-id: { staffTrackIds: [...] } }
partOrder: [ "part-id", ... ]
```

## Known Limitations

- Ornaments not included (articulations covered by `17_articulations.mscore.yaml`)
- Time signature changes mid-score not tested
