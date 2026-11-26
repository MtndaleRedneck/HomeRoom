package app.homeroom

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Private typed preview. Parsing a card never enables execution or grants authority. */
internal data class ActionReview(val kind:String,val fields:List<Pair<String,String>>,val canonical:String) {
    companion object {
        fun parse(action:JSONObject):ActionReview {
            val o=JSONObject(action.toString()) // Freeze caller-owned mutable JSON before validation.
            val out=mutableListOf<Pair<String,String>>()
            fun show(label:String,value:String) {out.add(label to value)}
            o.exact("action_id","kind","summary","authorized_actor","details")
            show("Action ID",o.identifier("action_id",64))
            val kind=o.str("kind",1,20);require(kind in setOf("email","purchase"))
            show("Summary",o.str("summary",1,240))
            val actor=o.obj("authorized_actor").apply {exact("actor_id","display_name","key_id")}
            show("Acting AI",actor.str("display_name",1,120))
            show("Exact AI identity",actor.identifier("actor_id",128))
            show("AI signing key",actor.identifier("key_id",128))
            val d=o.obj("details")
            if(kind=="email") {
                d.exact("sending_identity","to","cc","bcc","subject","body","attachments")
                val sender=d.obj("sending_identity").apply {exact("account_id","from_address","display_name")}
                show("Sending account",sender.identifier("account_id",128))
                show("Sender name",sender.str("display_name",0,120))
                show("Exact From address",sender.address("from_address"))
                var count=0
                for(category in listOf("to","cc","bcc")) {
                    val recipients=d.array(category);require(recipients.length()<=50)
                    if(category=="to")require(recipients.length()>=1)
                    count+=recipients.length()
                    if(recipients.length()==0)show(category.uppercase(),"None")
                    for(i in 0 until recipients.length()) {
                        val r=recipients.get(i) as? JSONObject ?: error("Recipient object required")
                        r.exact("name","address")
                        show(category.uppercase()+" name "+(i+1),r.str("name",0,120))
                        show(category.uppercase()+" exact address "+(i+1),r.address("address"))
                    }
                }
                require(count<=50)
                show("Exact subject",d.str("subject",0,998))
                val body=d.obj("body").apply {exact("format","text")}
                require(body.str("format",1,20)=="plain_text")
                show("Exact plain-text body",body.str("text",1,20000,80000,true))
                val files=d.array("attachments");require(files.length()<=10)
                var total=0L
                if(files.length()==0)show("Attachments","None")
                for(i in 0 until files.length()) {
                    val f=files.get(i) as? JSONObject ?: error("Attachment object required")
                    f.exact("file_id","name","byte_size","sha256")
                    show("Attachment "+(i+1),f.str("name",1,240))
                    show("Attachment ID "+(i+1),f.identifier("file_id",64))
                    val size=f.integer("byte_size",1,25L*1024*1024);total+=size
                    show("Attachment bytes "+(i+1),size.toString())
                    show("Attachment SHA-256 "+(i+1),f.digest("sha256"))
                }
                require(total<=25L*1024*1024)
                show("Attachment safety","The consumer must inspect these exact bytes and independently verify sharing with all To, CC and BCC recipients. Metadata is not permission.")
            } else {
                // TRUE private12 key set (B/f.java:241): total_components is required.
                d.exact("merchant","items","delivery","total_components","final_total_minor","currency","undo_terms","checkout_reference","payment_identity")
                val merchant=d.obj("merchant").apply {exact("name","origin")}
                show("Merchant",merchant.str("name",1,160))
                val origin=merchant.str("origin",1,300);val uri=URI(origin)
                require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.rawPath.isNullOrEmpty())
                show("Exact merchant origin",origin)
                val items=d.array("items");require(items.length() in 1..50)
                var lineTotalSum=0L
                for(i in 0 until items.length()) {
                    val item=items.get(i) as? JSONObject ?: error("Item object required")
                    item.exact("merchant_item_id","name","variant","quantity","unit_price_minor","line_total_minor")
                    show("Item "+(i+1),item.str("name",1,240))
                    show("Merchant item ID "+(i+1),item.identifier("merchant_item_id",128))
                    show("Variant "+(i+1),item.str("variant",0,500))
                    show("Quantity "+(i+1),item.integer("quantity",1,1000).toString())
                    show("Unit price "+(i+1),usd(item.integer("unit_price_minor",0,LIMIT)))
                    val lineTotal=item.integer("line_total_minor",0,LIMIT)
                    lineTotalSum+=lineTotal
                    show("Merchant line total "+(i+1),usd(lineTotal))
                }
                require(d.str("currency",3,3)=="USD")
                // TRUE private12 cost honesty (B/f.java:286-302): the component
                // breakdown is required and must reconcile exactly - summed
                // line totals equal items_minor, the discount never exceeds
                // items_minor, and final == items - discount + tax + shipping
                // + fees. An unverifiable total fails closed.
                val components=d.obj("total_components").apply {exact("items_minor","discount_minor","tax_minor","shipping_minor","fees_minor")}
                val itemsMinor=components.integer("items_minor",0,LIMIT)
                val discountMinor=components.integer("discount_minor",0,LIMIT)
                val taxMinor=components.integer("tax_minor",0,LIMIT)
                val shippingMinor=components.integer("shipping_minor",0,LIMIT)
                val feesMinor=components.integer("fees_minor",0,LIMIT)
                val finalMinor=d.integer("final_total_minor",0,LIMIT)
                require(lineTotalSum==itemsMinor && discountMinor<=itemsMinor && finalMinor==itemsMinor-discountMinor+taxMinor+shippingMinor+feesMinor)
                show("Items after item discounts",usd(itemsMinor))
                show("Order-level discount",usd(discountMinor))
                show("Tax",usd(taxMinor))
                show("Shipping",usd(shippingMinor))
                show("Fees",usd(feesMinor))
                show("Exact final total (tax, shipping and fees included)",usd(finalMinor))
                val delivery=d.obj("delivery").apply {exact("method","recipient_name","address","pickup_location")}
                val method=delivery.str("method",1,20);require(method in setOf("shipping","pickup"))
                show("Delivery method",method)
                show("Recipient",delivery.str("recipient_name",1,120))
                if(method=="shipping") {
                    require(delivery.isNull("pickup_location"))
                    val address=delivery.obj("address").apply {exact("line1","line2","city","region","postal_code","country_code")}
                    for(k in listOf("line1","line2","city","region","postal_code"))show("Delivery "+k,address.str(k,if(k=="line2")0 else 1,240))
                    val country=address.str("country_code",2,2);require(country.matches(Regex("[A-Z]{2}")));show("Delivery country",country)
                } else {
                    require(delivery.isNull("address"));show("Exact pickup location",delivery.str("pickup_location",1,1000))
                }
                val undo=d.obj("undo_terms").apply {exact("cancellable","cancel_until","fee_minor","currency","route","terms_text")}
                val cancellable=undo.get("cancellable") as? Boolean ?: error("Boolean required")
                show("Cancellable",if(cancellable)"Yes" else "No")
                show("Cancellation deadline",if(undo.isNull("cancel_until"))"Not specified" else undo.str("cancel_until",1,100).also {java.time.Instant.parse(it)})
                if(undo.isNull("fee_minor")) {require(undo.isNull("currency"));show("Undo cost","Unknown. Do not assume free cancellation.")}
                else {require(undo.str("currency",3,3)=="USD");show("Undo cost",usd(undo.integer("fee_minor",0,LIMIT)))}
                show("Cancellation route",if(undo.isNull("route"))"Not provided" else undo.str("route",1,1000))
                show("Exact material undo terms",undo.str("terms_text",1,4000))
                show("Checkout reference",d.identifier("checkout_reference",128))
                val p=d.obj("payment_identity").apply {exact("adapter_id","account_id","method_id","label","method_kind")}
                show("Payment method",p.str("label",1,120,480))
                val paymentKind=p.str("method_kind",1,40)
                require(paymentKind in setOf("saved_merchant_card","saved_vault_card","link_wallet","link_balance","shop_pay_one_time_card"))
                show("Payment kind",paymentKind)
                for(k in listOf("adapter_id","account_id","method_id"))show("Payment "+k,p.identifier(k,128))
                show("Payment safety","This preview does not establish that an adapter or account is available or selected. A separate reviewed consumer and any payment-provider approval remain required.")
            }
            return ActionReview(kind,out,canonicalDecisionJson(o))
        }
        private const val LIMIT=1000000000000L
        internal fun usd(minor:Long):String {require(minor in 0..LIMIT);return "$"+(minor/100)+"."+(minor%100).toString().padStart(2,'0')+" USD ("+minor+" minor units)"}
    }
}
private fun JSONObject.exact(vararg keys:String) {require(keys().asSequence().toSet()==keys.toSet())}
private fun JSONObject.obj(key:String)=get(key) as? JSONObject ?: error("Object required: "+key)
private fun JSONObject.array(key:String)=get(key) as? JSONArray ?: error("Array required: "+key)
private fun JSONObject.str(key:String,min:Int,max:Int,bytes:Int=max*4,body:Boolean=false):String {
    val s=get(key) as? String ?: error("String required: "+key)
    require(s.codePointCount(0,s.length) in min..max && s.toByteArray(Charsets.UTF_8).size<=bytes)
    require(s.none {it.code<32 && !(body && (it=='\n' || it=='\t')) || it.code in 127..159})
    var i=0
    while(i<s.length) {val c=s[i];if(Character.isHighSurrogate(c)) {require(i+1<s.length && Character.isLowSurrogate(s[i+1]));i+=2}else {require(!Character.isLowSurrogate(c));i++}}
    return s
}
private fun JSONObject.identifier(key:String,max:Int)=str(key,1,max).also {require(it.matches(Regex("[A-Za-z0-9_.:@+\\-]+")))}
private fun JSONObject.address(key:String)=str(key,3,254).also {require(it.count {c->c=='@'}==1 && it.none {c->c.isWhitespace()})}
private fun JSONObject.digest(key:String)=str(key,64,64).also {require(it.matches(Regex("[a-f0-9]{64}")))}
private fun JSONObject.integer(key:String,min:Long,max:Long):Long {
    val n=get(key);require(n is Int || n is Long);return (n as Number).toLong().also {require(it in min..max)}
}
// RECONSTRUCTED Nov 23, 2026: recovered exactly from the exact private12
// release build. isYesNo is the yes_no + exact Yes/No option pair check;
// immutableSnapshot is the server-comparable snapshot object; discussionConfirmable
// is the release's discussion-card validity check (yes_no, no action fields,
// discussion effect, length bounds, matching content hash).
internal val DecisionCard.isYesNo:Boolean get()=decisionType=="yes_no" && options==listOf(DecisionOption("yes","Yes"),DecisionOption("no","No"))
internal fun DecisionCard.immutableSnapshot():JSONObject=JSONObject()
    .put("action",action ?: JSONObject.NULL)
    .put("action_sha256",actionHash ?: JSONObject.NULL)
    .put("context_event_ids",JSONArray(contextEventIds))
    .put("decision_type",decisionType ?: JSONObject.NULL)
    .put("effect_class",effect)
    .put("options",JSONArray(options.map {JSONObject().put("option_id",it.id).put("label",it.label)}))
    .put("proposal",proposal)
    .put("title",title)
internal val DecisionCard.discussionConfirmable:Boolean get()=try {
    require(isYesNo && action==null && actionHash==null && effect=="discussion")
    require(title.length in 1..120 && proposal.length in 1..8000)
    require(sha256(canonicalDecisionJson(immutableSnapshot()).toByteArray(Charsets.UTF_8))==hash)
    true
}catch(_:Exception) {false}
internal fun DecisionCard.actionReviewOrNull():ActionReview?=try {
    require(isYesNo && action!=null && actionHash!=null)
    // RECONSTRUCTED: the release also bounds the snapshot before hashing.
    require(title.length in 1..120 && proposal.length in 1..8000 && proposal.toByteArray(Charsets.UTF_8).size<=32000)
    val snapshotCanonical=canonicalDecisionJson(immutableSnapshot())
    require(snapshotCanonical.toByteArray(Charsets.UTF_8).size<=38000)
    val review=ActionReview.parse(action)
    require((review.kind=="email" && effect=="external_action") || (review.kind=="purchase" && effect=="money"))
    require(sha256(review.canonical.toByteArray(Charsets.UTF_8))==actionHash)
    require(sha256(snapshotCanonical.toByteArray(Charsets.UTF_8))==hash)
    review
}catch(_:Exception) {null}
