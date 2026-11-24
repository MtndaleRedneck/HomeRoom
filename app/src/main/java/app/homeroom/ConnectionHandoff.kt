package app.homeroom

import android.content.Intent
import java.net.URI

internal data class ConnectionHandoff(val origin:String,val code:String) {
    val link get()=origin+"/homeroom/connect#token="+android.net.Uri.encode(code)
    companion object {
        fun from(intent:Intent):ConnectionHandoff? {
            if(intent.action!=Intent.ACTION_VIEW)return null
            val uri=try {URI(intent.dataString ?: return null)}catch(_:Exception) {return null}
            if(uri.scheme!="homeroom" || uri.rawAuthority!="connect" || !uri.rawPath.isNullOrEmpty() || uri.rawQuery!=null || uri.rawFragment!=null)return null
            val origin=intent.getStringExtra("server_origin") ?: return null
            val code=intent.getStringExtra("enrollment_code") ?: return null
            if(origin.length>2048 || code.length !in 1..4096 || (origin+code).any {it.isWhitespace() || it.isISOControl()})return null
            val parsed=try {URI(origin)}catch(_:Exception) {return null}
            if(parsed.scheme!="https" || parsed.host.isNullOrBlank() || parsed.userInfo!=null || parsed.query!=null || parsed.fragment!=null || parsed.rawPath !in listOf("","/"))return null
            if(parsed.port !in listOf(-1,443))return null
            val normalized=URI("https",null,parsed.host.lowercase(),-1,null,null,null).toString()
            return ConnectionHandoff(normalized,code)
        }
    }
}