package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.ReductionId
import com.mecon.api.runtime.toStorage
import com.mecon.core.analysis.ReductionEngine
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.uikit.theme.MeconColors

/** Compact overview for the Analysis / Create workspace. It keeps the score visible while making
 * the active reduction and orchestration state explicit. */
@Composable
internal fun AnalysisOverviewPanel(
    session: ScoreSession,
    selectedReductionId: ReductionId?,
    onReductionSelected: (ReductionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = session.runtimeScore ?: return
    val storage = remember(runtime) { runtime.toStorage() }
    val selected = storage.reductions.firstOrNull { it.id == selectedReductionId }
    val report = selected?.let { ReductionEngine.consistency(storage, it) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(MeconColors.SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(170.dp)) {
            Text("分析/创作", color = MeconColors.TextPrimary, fontSize = 13.sp)
            Text(
                if (storage.reductions.isEmpty()) {
                    "先创建空缩谱"
                } else {
                    "右侧切换层；素材台保存未定位材料"
                },
                color = MeconColors.TextSecondary,
                fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            storage.reductions.forEach { reduction ->
                val active = reduction.id == selectedReductionId
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) MeconColors.SelectedSurface else MeconColors.Surface)
                        .clickable { onReductionSelected(reduction.id) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(reduction.title, color = MeconColors.TextPrimary, fontSize = 11.sp)
                    if (active && report != null) {
                        Text(
                            "映射 ${report.links.size} · 待实现 ${report.unrealizedTargets.size} · 素材 ${reduction.materialTray.size}",
                            color = MeconColors.TextSecondary,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val orchestration = storage.orchestration
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (orchestration == null) "演奏者：未配置" else "演奏者 ${orchestration.players.size} · 内容线 ${orchestration.lines.size}",
                color = MeconColors.TextSecondary,
                fontSize = 10.sp,
            )
            if (orchestration != null) {
                Text(
                    "演奏者标签显示在缩谱配器层（总谱链接 ${orchestration.links.size}）",
                    color = MeconColors.TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}
