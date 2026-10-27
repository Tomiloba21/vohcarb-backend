# Word games: design

*2026-10-27. Decisions marked ✅ were made by the author; the rest are proposals to confirm. G1 (daily challenges,
backend) is built; see the README's "Word games" section for the API.*

G1 differs from this draft in one way: in the **global** daily challenge, unlisted names and places simply score 0
for now; the "submit for review" step will come with live play (G3).

Three games, played four ways, with friends or strangers. The backend owns all rules, timers and scoring so
results can't be faked from a phone; the app only shows rounds and sends answers.

## The games

### 1. Name, Place, Animal, Thing ✅

Everyone gets the same random letter and has **60 s** to write one answer per category.

| Category | Valid when | Source |
|---|---|---|
| Animal | in the catalog's `animals` category (3,151 words) | WordNet (have it) |
| Thing | in the catalog's `objects` category (8,426 words) | WordNet (have it) |
| Place | a country or a city in the place list | **GeoNames** `countryInfo` + `cities15000` (CC BY 4.0; credit in the app) |
| Name | a first name in the names list | **US SSA baby names** (public domain), plus names added through votes |

✅ Answers not found in the lists go to a **vote**: the other players see them for 15 s and can reject them;
an answer stands unless most voters reject it. Answers that survive a vote can be added to the lists (after
a few acceptances), so local names like *Tobiloba* or places like *Ikorodu* get recognised over time.

- Letters: A–Z without Q, X, Z (too few answers); never the same letter twice in a match.
- Answers must start with the letter; case, spaces and accents are ignored.
- Scoring per answer: **10** if valid and nobody else gave it, **5** if valid and shared, **0** if invalid or
  empty. A match is **5 rounds**.
- Where nobody can vote (global daily challenge), unlisted answers score **0** with "not in our list yet",
  and the player can submit them for review.

### 2. Category sprint ✅

A category from the catalog (e.g. *Food & drink*); **60 s** to type as many words in it as possible.

- A word counts if it's in the catalog in that category; duplicates and sensitive words don't count.
- Points = the word's **level** (1 everyday … 5 rare), so knowing rarer words pays.
- Categories with too few everyday words (e.g. *Motives*, 54 words) are never picked.

### 3. Complete the word ✅ (difficulty decides the hint)

The meaning is shown; the player types the word. **10 words** per match.

| Difficulty | Words from levels | Hint |
|---|---|---|
| Easy | 1–2 | meaning + about half the letters (`s _ r _ n d _ p _ t y`) |
| Medium | 2–3 | meaning + about a third of the letters |
| Hard | 3–4 | meaning + first letter + length (`s _ _ _ _ _ _ _ _ _ _`) |

- 20 s per word. Points = 10 × difficulty multiplier (1 / 2 / 3) + a speed bonus (up to 5).
- "Reveal a letter" costs 3 points (never below 0 for that word).
- Words come from the same pool as the quiz: no sensitive words, prefer words with a single clear meaning.

## Ways to play ✅ (like chess.com)

| Mode | Who | How |
|---|---|---|
| **Daily challenge** | everyone, and each group | Same rounds for everyone that UTC day (per game). Play once, any time; global and group leaderboards. |
| **Play a friend** | your groups | Create a room, invite group members (or share a room code); starts when the host starts it. |
| **Quick match** | random players | Pick a game; you're matched with 1–5 others waiting for the same game; if nobody turns up within 30 s, you're offered a solo round. |
| **Open lobby** | anyone | A list of public rooms waiting for players; join one, or open your own. |

Live modes (friend, quick match, lobby) are played at the same moment with a shared countdown and live scores;
daily challenges are played alone and compared afterwards.

## How it fits the system

- **Backend, new `game/` package**: content (letters, categories, word picks), validation, scoring, daily
  challenges (generated from the date like the word of the day, so nothing is stored until people play),
  results and leaderboards. Pure Kotlin rules with unit tests, like `Scheduler.kt`.
- **Live play over WebSockets** (`/ws/game`, JSON messages, the same JWT). The server runs each room's
  timeline (lobby → round → vote → results) and pushes updates; phones send answers. Rooms live in memory
  on the single server, which suits one VPS; a restart ends running matches (scores of finished matches
  are saved).
- **App**: a Games tab (or a section under Groups) with the daily challenges, quick match, lobby list and
  friend rooms; Ktor's WebSocket client works on Android and iOS.
- **Lists**: imported like the WordNet catalog (`--spring.profiles.active=import`), files not in git.
- **New credits**: GeoNames (CC BY 4.0), SSA names (public domain).

## Phases

| Phase | Repo | What |
|---|---|---|
| G1 | backend | Game rules and scoring, word picks, name/place lists and importer, daily challenges (global + group), results, leaderboards, REST API |
| G2 | app | Games tab: play the three daily challenges solo, results, leaderboards |
| G3 | backend | Live play: WebSocket protocol, rooms, friend invites, quick-match queue, open lobbies, voting |
| G4 | app | Live play UI: lobby list, matchmaking, room, live rounds, voting, results |

Each phase is usable on its own: after G2, friends can already compete on the daily challenges.

## Still to decide

1. Match sizes for live play: 2–6 players? (proposed)
2. Should games affect learning (e.g. words you missed in Complete-the-word get saved for practice)? Proposed: offer a
   "save the words I missed" button at the end, off by default.
3. Ratings like chess.com's Elo for quick match, or just scores? Proposed: scores first, ratings later if wanted.
