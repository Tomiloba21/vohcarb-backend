package app.vohcarb.game

import app.vohcarb.social.GroupMemberRepository
import app.vohcarb.social.GroupService
import app.vohcarb.user.AppUserRepository
import app.vohcarb.user.now
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily challenges: every challenge once a day (UTC), globally and in each of your groups. Plays are timed by
 * the server; answers are checked and scored here. Name-Place-Animal-Thing points depend on what everyone else
 * answered (unique answers score more) and on group votes, so scores are worked out when read.
 */
@Service
@Transactional
class DailyGameService(
    private val plays: GamePlayRepository,
    private val answers: GameAnswerRepository,
    private val votes: GameVoteRepository,
    private val content: DailyContent,
    private val lists: AnswerLists,
    private val groups: GroupService,
    private val members: GroupMemberRepository,
    private val users: AppUserRepository,
) {

    @Transactional(readOnly = true)
    fun overview(userId: Long, groupId: Long?): DailyOverviewDto {
        val scope = scope(userId, groupId)
        val date = today()
        val mine = plays.findByUserIdAndPlayDateAndScopeKey(userId, date, scope).associateBy { it.challenge }
        val challenges = Challenge.entries.map { challenge ->
            val play = mine[challenge]
            val status = when {
                play == null -> PlayStatus.NOT_STARTED
                play.submittedAt != null || expired(play) -> PlayStatus.DONE
                else -> PlayStatus.IN_PROGRESS
            }
            val score = if (status == PlayStatus.DONE) board(challenge, date, scope).totals[play!!.id] ?: 0 else null
            DailyChallengeDto(
                challenge, challenge.game, challenge.hint, challenge.title, challenge.description,
                challenge.timeLimitSeconds, status, score, play?.id,
            )
        }
        return DailyOverviewDto(date, groupId, challenges)
    }

    /** Starts today's challenge, or resumes it (the clock keeps running). Each challenge can be played once a day. */
    fun start(userId: Long, challenge: Challenge, groupId: Long?): PlayDto {
        val scope = scope(userId, groupId)
        val date = today()
        val existing = plays.findByUserIdAndChallengeAndPlayDateAndScopeKey(userId, challenge, date, scope)
        val play = when {
            existing == null -> try {
                plays.saveAndFlush(GamePlay(userId, challenge, date, groupId, scope))
            } catch (_: DataIntegrityViolationException) {
                conflict("This challenge was just started on another device") // two starts at once
            }
            existing.submittedAt != null || expired(existing) ->
                conflict("You've already played today's ${challenge.title}. A new one starts at midnight UTC.")
            else -> existing
        }
        return playDto(play)
    }

    /** Complete-the-word: shows one more letter of a word, at a cost (see [CompleteRules.REVEAL_COST]). */
    fun reveal(userId: Long, playId: Long, index: Int): HintDto {
        val play = openPlay(userId, playId)
        val words = (content(play) as? Content.Complete ?: badRequest("Only complete-the-word has letters to reveal")).words
        val word = words.getOrNull(index) ?: badRequest("No word $index")
        val counts = play.revealCounts(words.size)
        val before = masked(word, counts[index])
        if (CompleteRules.HIDDEN in before) {
            counts[index]++
            play.reveals = counts.joinToString(",")
        }
        return hint(index, word, counts[index])
    }

    /** Hands in the answers. After the deadline (plus [GRACE]) the play still ends, but the answers don't count. */
    fun submit(userId: Long, playId: Long, request: SubmitRequest): ResultDto {
        val play = ownPlay(userId, playId)
        if (play.submittedAt != null) conflict("Already handed in")
        val at = now()
        play.submittedAt = at
        if (!at.isAfter(deadline(play).plus(GRACE))) {
            val id = play.id!!
            val rows = when (val c = content(play)) {
                is Content.Npat -> npatAnswers(id, c, request.rounds.orEmpty())
                is Content.Sprint -> sprintAnswers(id, c, request.words.orEmpty())
                is Content.Complete -> completeAnswers(id, c, request.answers.orEmpty(), play.revealCounts(c.words.size))
            }
            answers.saveAll(rows)
        }
        plays.flush()
        answers.flush()
        return result(userId, playId)
    }

    @Transactional(readOnly = true)
    fun result(userId: Long, playId: Long): ResultDto {
        val play = ownPlay(userId, playId)
        if (play.submittedAt == null) conflict("Hand in your answers first")
        val board = board(play.challenge, play.playDate, play.scopeKey)
        val complete = content(play) as? Content.Complete
        val rows = answers.findByPlayIdIn(listOf(play.id!!)).sortedWith(compareBy({ it.item }, { it.slot })).map { a ->
            AnswerResultDto(
                id = a.id!!,
                item = a.item,
                category = a.slot.ifEmpty { null },
                answer = a.answer,
                verdict = a.verdict,
                stands = board.stands[a.id] ?: false,
                points = board.points[a.id] ?: 0,
                correctAnswer = complete?.words?.getOrNull(a.item)?.word,
            )
        }
        val late = play.submittedAt!!.isAfter(deadline(play).plus(GRACE))
        return ResultDto(play.id!!, play.challenge, play.playDate, board.totals[play.id] ?: 0, late, rows)
    }

    /** Players ranked by score; equal scores share a rank. Global boards show the top [TOP] and you. */
    @Transactional(readOnly = true)
    fun leaderboard(userId: Long, challenge: Challenge, groupId: Long?, date: LocalDate?): GameLeaderboardDto {
        val scope = scope(userId, groupId)
        val day = date ?: today()
        val board = board(challenge, day, scope)
        val names = users.findAllById(board.plays.map { it.userId }.toSet()).associate { it.id!! to it.displayName }
        val sorted = board.plays.sortedWith(
            compareByDescending<GamePlay> { board.totals[it.id] ?: 0 }.thenBy { names[it.userId].orEmpty().lowercase() },
        )
        var rank = 0
        var previous: Int? = null
        val all = sorted.mapIndexed { index, play ->
            val score = board.totals[play.id] ?: 0
            if (score != previous) rank = index + 1
            previous = score
            GameLeaderboardRow(rank, play.userId, names[play.userId].orEmpty(), score, play.userId == userId)
        }
        val rows = if (scope == GLOBAL) all.filterIndexed { i, row -> i < TOP || row.isMe } else all
        return GameLeaderboardDto(challenge, day, groupId, all.size, rows)
    }

    /** Group Name-Place-Animal-Thing: other members' answers that aren't in the lists, today, to accept or reject. */
    @Transactional(readOnly = true)
    fun review(userId: Long, groupId: Long): List<ReviewItemDto> {
        groups.requireMember(userId, groupId)
        val date = today()
        val others = plays.findSubmitted(Challenge.NAME_PLACE_ANIMAL_THING, date, groupId).filter { it.userId != userId }
        if (others.isEmpty()) return emptyList()
        val letters = (content.forDay(Challenge.NAME_PLACE_ANIMAL_THING, date, groupId) as Content.Npat).letters
        val names = users.findAllById(others.map { it.userId }.toSet()).associate { it.id!! to it.displayName }
        val playersByPlay = others.associate { it.id!! to it.userId }
        val unlisted = answers.findByPlayIdIn(playersByPlay.keys).filter { it.verdict == Verdict.UNLISTED }
        val votesByAnswer = votes.findByAnswerIdIn(unlisted.map { it.id!! }).groupBy { it.answerId }
        return unlisted.sortedWith(compareBy({ it.item }, { it.slot })).map { a ->
            val v = votesByAnswer[a.id].orEmpty()
            ReviewItemDto(
                answerId = a.id!!,
                playerName = names[playersByPlay[a.playId]].orEmpty(),
                letter = letters[a.item].uppercase(),
                category = a.slot,
                answer = a.answer,
                accepts = v.count { it.accepted },
                rejects = v.count { !it.accepted },
                myVote = v.firstOrNull { it.voterId == userId }?.accepted,
            )
        }
    }

    fun vote(userId: Long, answerId: Long, accepted: Boolean) {
        val answer = answers.findById(answerId).orElseThrow { notFound("No answer $answerId") }
        val play = plays.findById(answer.playId).orElseThrow { notFound("No answer $answerId") }
        val groupId = play.groupId ?: badRequest("Only answers in group challenges can be voted on")
        groups.requireMember(userId, groupId) // 404 to outsiders
        if (answer.verdict != Verdict.UNLISTED) badRequest("Only answers that aren't in the lists can be voted on")
        if (play.userId == userId) badRequest("You can't vote on your own answer")
        if (play.playDate != today()) conflict("Voting on that day's answers has closed")
        val existing = votes.findById(GameVoteId(answerId, userId)).orElse(null)
        if (existing != null) existing.accepted = accepted else votes.save(GameVote(answerId, userId, accepted))
    }

    // Answers

    private fun npatAnswers(playId: Long, c: Content.Npat, rounds: List<NpatRoundAnswers>): List<GameAnswer> {
        data class Raw(val round: Int, val category: NpatRules.Category, val text: String, val normalized: String)
        val raw = rounds.take(c.letters.size).flatMapIndexed { round, r ->
            listOf(
                NpatRules.Category.NAME to r.name, NpatRules.Category.PLACE to r.place,
                NpatRules.Category.ANIMAL to r.animal, NpatRules.Category.THING to r.thing,
            ).mapNotNull { (category, text) ->
                text?.trim()?.take(MAX_ANSWER)?.takeIf { it.isNotEmpty() }?.let { Raw(round, category, it, AnswerText.normalize(it)) }
            }
        }
        fun keys(category: NpatRules.Category) = raw.filter { it.category == category }.map { it.normalized }
        val names = lists.knownNames(keys(NpatRules.Category.NAME))
        val places = lists.knownPlaces(keys(NpatRules.Category.PLACE))
        val animals = lists.wordsInCategory(AnswerLists.ANIMALS, keys(NpatRules.Category.ANIMAL)).keys
        val things = lists.wordsInCategory(AnswerLists.THINGS, keys(NpatRules.Category.THING)).keys
        return raw.map { r ->
            val verdict = when {
                r.normalized.isEmpty() || r.normalized.first() != c.letters[r.round] -> Verdict.INVALID
                r.category == NpatRules.Category.NAME -> listedOr(r.normalized in names, r.normalized)
                r.category == NpatRules.Category.PLACE -> listedOr(r.normalized in places, r.normalized)
                r.category == NpatRules.Category.ANIMAL -> if (r.normalized in animals) Verdict.VALID else Verdict.INVALID
                else -> if (r.normalized in things) Verdict.VALID else Verdict.INVALID
            }
            GameAnswer(playId, r.round, r.category.name, r.text, r.normalized, verdict, points = 0)
        }
    }

    /** Names and places missing from the lists go to a vote, unless they're too short to be real. */
    private fun listedOr(listed: Boolean, normalized: String) = when {
        listed -> Verdict.VALID
        normalized.length >= 2 -> Verdict.UNLISTED
        else -> Verdict.INVALID
    }

    private fun sprintAnswers(playId: Long, c: Content.Sprint, words: List<String>): List<GameAnswer> {
        val typed = words.map { it.trim().take(MAX_ANSWER) }.filter { it.isNotEmpty() }
            .map { it to AnswerText.normalize(it) }.filter { it.second.isNotEmpty() }.distinctBy { it.second }
        val known = lists.wordsInCategory(c.category.slug, typed.map { it.second })
        return typed.mapIndexed { i, (text, normalized) ->
            val valid = normalized in known
            GameAnswer(playId, i, "", text, normalized, if (valid) Verdict.VALID else Verdict.INVALID, if (valid) SprintRules.points(known[normalized]) else 0)
        }
    }

    private fun completeAnswers(playId: Long, c: Content.Complete, given: List<CompleteAnswer>, reveals: List<Int>): List<GameAnswer> =
        given.distinctBy { it.index }.filter { it.index in c.words.indices }.map { a ->
            val text = a.answer.trim().take(MAX_ANSWER)
            val normalized = AnswerText.normalize(text)
            val correct = normalized == c.words[a.index].word
            GameAnswer(
                playId, a.index, "", text, normalized,
                if (correct) Verdict.VALID else Verdict.INVALID,
                CompleteRules.points(correct, c.hint, a.elapsedMs, reveals[a.index]),
                a.elapsedMs,
            )
        }

    // Scores

    private class Board(val plays: List<GamePlay>, val totals: Map<Long?, Int>, val points: Map<Long?, Int>, val stands: Map<Long?, Boolean>)

    /** Everyone's points for one challenge on one day in one scope (group boards: current members only). */
    private fun board(challenge: Challenge, date: LocalDate, scope: Long): Board {
        var submitted = plays.findSubmitted(challenge, date, scope)
        if (scope != GLOBAL) {
            val current = members.findMembers(scope).map { it.user.id!! }.toSet()
            submitted = submitted.filter { it.userId in current }
        }
        val all = answers.findByPlayIdIn(submitted.map { it.id!! })
        val points = HashMap<Long?, Int>()
        val stands = HashMap<Long?, Boolean>()

        if (challenge.game == Game.NAME_PLACE_ANIMAL_THING) {
            val voted = votes.findByAnswerIdIn(all.filter { it.verdict == Verdict.UNLISTED }.map { it.id!! }).groupBy { it.answerId }
            all.forEach { a ->
                stands[a.id] = when (a.verdict) {
                    Verdict.VALID -> true
                    Verdict.INVALID -> false
                    Verdict.UNLISTED -> voted[a.id].orEmpty().let { v ->
                        NpatRules.unlistedStands(v.count { it.accepted }, v.count { !it.accepted }, votingPossible = scope != GLOBAL)
                    }
                }
            }
            // Unique or shared is decided per round and category, among answers that stand
            all.groupBy { it.item to it.slot }.values.forEach { group ->
                val scored = NpatRules.score(group.associate { it.id to (if (stands[it.id] == true) it.normalized else null) })
                points.putAll(scored)
            }
        } else {
            all.forEach { a ->
                stands[a.id] = a.verdict == Verdict.VALID
                points[a.id] = a.points
            }
        }
        val byPlay = all.groupBy { it.playId }
        val totals = submitted.associate { p -> p.id to byPlay[p.id].orEmpty().sumOf { points[it.id] ?: 0 } }
        return Board(submitted, totals, points, stands)
    }

    // Plays

    private fun playDto(play: GamePlay): PlayDto {
        val base = PlayDto(play.id!!, play.challenge, play.playDate, play.startedAt, deadline(play))
        return when (val c = content(play)) {
            is Content.Npat -> base.copy(letters = c.letters.map { it.uppercase() })
            is Content.Sprint -> base.copy(category = c.category)
            is Content.Complete -> {
                val counts = play.revealCounts(c.words.size)
                base.copy(words = c.words.mapIndexed { i, w -> hint(i, w, counts[i]) })
            }
        }
    }

    private fun hint(index: Int, word: CompleteWord, reveals: Int) =
        HintDto(index, word.definition, word.partOfSpeech, word.word.length, masked(word, reveals))

    private fun masked(word: CompleteWord, reveals: Int) =
        (1..reveals).fold(word.mask) { mask, _ -> CompleteRules.reveal(mask, word.word) }

    private fun content(play: GamePlay) = content.forDay(play.challenge, play.playDate, play.scopeKey)

    private fun deadline(play: GamePlay) = play.startedAt.plusSeconds(play.challenge.timeLimitSeconds.toLong())

    private fun expired(play: GamePlay) = play.submittedAt == null && now().isAfter(deadline(play).plus(GRACE))

    /** Yours (others' plays are 404) and still open. */
    private fun openPlay(userId: Long, playId: Long): GamePlay {
        val play = ownPlay(userId, playId)
        if (play.submittedAt != null || expired(play)) conflict("This play has ended")
        return play
    }

    private fun ownPlay(userId: Long, playId: Long): GamePlay =
        plays.findById(playId).filter { it.userId == userId }.orElseThrow { notFound("No play $playId") }

    private fun scope(userId: Long, groupId: Long?): Long {
        if (groupId == null) return GLOBAL
        groups.requireMember(userId, groupId)
        return groupId
    }

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun notFound(message: String) = ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        /** Network delay allowed after the deadline. */
        val GRACE: Duration = Duration.ofSeconds(30)
        const val GLOBAL = 0L
        const val TOP = 50
        const val MAX_ANSWER = 64
    }
}
