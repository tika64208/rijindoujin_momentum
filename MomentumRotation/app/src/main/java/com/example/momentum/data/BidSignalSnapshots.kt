package com.example.momentum.data

import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.data.db.BidSignalSnapshotEntity
import com.example.momentum.data.db.toEntity
import com.example.momentum.engine.BidStrategy

object BidSignalSnapshots {
    fun legacy(e: BidDataEntity): BidSignalSnapshotEntity = BidSignalSnapshotEntity(
        e.copy(latestPrice = e.openPrice, realChange = e.bidChange, volume = 0, amount = 0.0, turnover = 0.0),
        if (e.savedAt in "09:15:00".."09:30:00" && e.probability > 0) "LEGACY_EARLY" else "UNVERIFIED",
        "legacy-stored", "", 0L)

    /** 从档案中已冻结的 CSV 评分读取结果，不调用当前评分模型重算旧档案。 */
    fun opening(archive: OpeningBidArchive): List<BidSignalSnapshotEntity> {
        val records = parseCsv(archive.csv)
        require(records.isNotEmpty()) { "冻结档案缺少评分 CSV" }
        val header = records.first()
        fun column(name: String): Int = header.indexOf(name).also { require(it >= 0) { "冻结档案缺少 $name" } }
        val codeIndex = header.indexOfFirst { it == "f12" || it.endsWith("[f12]") }
        require(codeIndex >= 0) { "冻结档案缺少股票代码" }
        val p = column("冻结概率"); val c = column("冻结可信度"); val s = column("冻结评分")
        val bt = column("冻结竞价换手率")
        // 估算竞价量是评分量能项的依据，档案里存了就一并还原，旧档案缺列时按 0 处理。
        val bv = header.indexOfFirst { it == "冻结估算竞价量" }
        val version = column("评分版本")
        val data = org.json.JSONObject(archive.rawJson).getJSONObject("data").getJSONArray("diff")
        val raw = (0 until data.length()).associate { i -> data.getJSONObject(i).let { it.getString("f12") to it.toString() } }
        val items = EastmoneyBidDataSource.parseItems(archive.rawJson).associateBy { it.code }
        val savedAt = java.time.Instant.ofEpochMilli(archive.receivedAt).atZone(OpeningBidTime.zone).toLocalTime().toString()
        val result = records.drop(1).filter { it.any(String::isNotEmpty) }.map { row ->
            require(row.size == header.size) { "冻结评分 CSV 列数不一致" }
            val item = requireNotNull(items[row[codeIndex]])
            val frozen = BidStrategy.BidResult(item = item, probability = row[p].toInt(), confidence = row[c].toInt(),
                score = row[s].toInt(), bidTurnover = row[bt].toDouble(),
                bidVolume = if (bv >= 0) row[bv].toIntOrNull() ?: 0 else 0)
            require(frozen.probability in 0..100 && frozen.confidence in 0..100 && frozen.score in 0..100 && frozen.bidTurnover.isFinite()) {
                "冻结评分数值无效"
            }
            BidSignalSnapshotEntity(item.toEntity(archive.date, frozen, savedAt).copy(
                latestPrice = item.openPrice, realChange = item.bidChange), "OPENING_0930", row[version],
                requireNotNull(raw[item.code]), archive.receivedAt)
        }
        require(result.size == archive.rowCount && result.map { it.signal.code }.toSet().size == archive.rowCount) {
            "冻结评分覆盖数量与全市场条数不一致"
        }
        return result
    }

    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var index = 0
        val input = text.removePrefix("\uFEFF")
        while (index < input.length) {
            val ch = input[index]
            when {
                ch == '"' && quoted && index + 1 < input.length && input[index + 1] == '"' -> { cell.append('"'); index++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { row.add(cell.toString()); cell.clear() }
                ch == '\n' && !quoted -> { row.add(cell.toString().removeSuffix("\r")); cell.clear(); rows.add(row); row = mutableListOf() }
                else -> cell.append(ch)
            }
            index++
        }
        require(!quoted) { "冻结评分 CSV 引号未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}
