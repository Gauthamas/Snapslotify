package `in`.gauthama.snapslotify.core.score

/**
 * The answer key for one case, as in the notebook's NEW_KEYS.
 * Each expected event maps a field (event, time, date) to accepted phrases; a null list is the UNCONFIRMED date
 * (null, or kept with the relative_date flag). flaggedOk: no event expected, but events that all carry relative_date pass.
 * payeeCases: the case is a bill (its accepted title phrases name the payee or the bill), for the alternative title rule.
 */
data class Key(
    val events: List<Map<String, List<String>?>>,
    val forbid: List<String> = emptyList(),
    val flaggedOk: Boolean = false,
    val payeeCases: Boolean = false,
)
