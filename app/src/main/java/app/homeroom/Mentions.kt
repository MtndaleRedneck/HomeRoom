package app.homeroom

/** Resolve only unambiguous visible names, using the same boundaries for sending and highlighting. */
object Mentions {
    private fun matches(text:String,members:List<Pair<String,String>>):List<Pair<IntRange,String>> {
        val names=members.filter {it.second.isNotBlank()}.groupBy {it.second.trim().lowercase()}
        val result=mutableListOf<Pair<IntRange,String>>()
        names.entries.sortedByDescending {it.key.length}.forEach {(_,people) ->
            if(people.size!=1 || !people[0].first.startsWith("ai_"))return@forEach
            val (actor,displayName)=people[0]
            val pattern=Regex("(?<![\\p{L}\\p{N}\\p{M}_@])@"+Regex.escape(displayName.trim())+"(?![\\p{L}\\p{N}\\p{M}_])",RegexOption.IGNORE_CASE)
            pattern.findAll(text).forEach {match ->
                if(result.none {it.first.first<=match.range.last && match.range.first<=it.first.last})result.add(match.range to actor)
            }
        }
        return result
    }
    fun resolve(text:String,members:List<Pair<String,String>>):List<String> = matches(text,members).map {it.second}.distinct()
    fun ranges(text:String,members:List<Pair<String,String>>):List<Pair<IntRange,String>> = matches(text,members).sortedBy {it.first.first}
}
