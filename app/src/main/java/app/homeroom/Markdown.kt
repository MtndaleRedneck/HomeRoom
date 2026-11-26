package app.homeroom

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface

/** Small, local-only formatter. No HTML, image loading, or network preview. */
object Markdown {
    fun render(source:String):SpannableStringBuilder {
        val out=SpannableStringBuilder(source.replace(Regex("(?m)^[-*] "),"• "))
        fun apply(pattern:Regex,monospace:Boolean=false,style:Int=Typeface.NORMAL) {
            pattern.findAll(out.toString()).toList().asReversed().forEach {m ->
                val inner=m.groups[1] ?: return@forEach
                val left=inner.range.first-m.range.first
                val right=m.range.last-inner.range.last
                out.delete(inner.range.last+1,inner.range.last+1+right)
                out.delete(m.range.first,m.range.first+left)
                val end=m.range.first+inner.value.length
                out.setSpan(if(monospace) TypefaceSpan("monospace") else StyleSpan(style),m.range.first,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        apply(Regex("```(?:[^\\n`]*\\n)?([\\s\\S]*?)```"),true)
        apply(Regex("`([^`\\n]+)`"),true)
        apply(Regex("\\*\\*([^*]+)\\*\\*"),style=Typeface.BOLD)
        apply(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"),style=Typeface.ITALIC)
        android.text.util.Linkify.addLinks(out,android.text.util.Linkify.WEB_URLS)
        return out
    }
}