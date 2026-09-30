import com.mytt.selector.Mytt
import com.mytt.selector.Mytt.Kline
import java.io.File

/**
 * Verify the Kotlin port against the known-good Python results.
 * Reads exported CSVs (date,open,high,low,close,volume,amount) and prints signals.
 */
fun main(args: Array<String>) {
    val cases = listOf(
        Triple("/tmp/kt_002635.csv", "002635", "安洁科技"),   // expected: signal=True (combined hit)
        Triple("/tmp/kt_603222.csv", "603222", "济民健康"),   // expected: signal=True
        Triple("/tmp/kt_603949.csv", "603949", "雪龙集团"),   // expected: sig_wanshou=True, sig_xlj4=False
        Triple("/tmp/kt_000001.csv", "000001", "平安银行"),   // expected: all False
    )
    for ((path, code, name) in cases) {
        val lines = File(path).readLines().drop(1)
        val n = lines.size
        val date = Array(n) { "" }
        val open = DoubleArray(n); val high = DoubleArray(n); val low = DoubleArray(n)
        val close = DoubleArray(n); val vol = DoubleArray(n); val amount = DoubleArray(n)
        for ((i, ln) in lines.withIndex()) {
            val p = ln.split(",")
            date[i] = p[0]
            open[i] = p[1].toDouble(); high[i] = p[2].toDouble(); low[i] = p[3].toDouble()
            close[i] = p[4].toDouble(); vol[i] = p[5].toDouble(); amount[i] = p[6].toDouble()
        }
        val k = Kline(date, open, high, low, close, vol, amount)
        val r = Mytt.compute(k, code, name)
        println("$code $name date=${r.date} close=%.2f chg=%.2f wanshou=%s xlj4=%s signal=%s WSD_25=%s XLJ4_7=%.1f bars=%d"
            .format(r.close, r.chgPct, r.sigWanshou, r.sigXlj4, r.signal, r.wsd25, r.xlj47, r.bars))
    }
}
